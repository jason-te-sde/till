# The four services of docker-compose.yml, on Fargate: the same images, the same environment, with
# Cognito for Keycloak, RDS for postgres and ElastiCache for redis. ARM64, which Fargate bills about
# a fifth below x86, and which is what the images are built as on the machine that pushes them.
#
# They start in order — the broker, the ledger, the store, the edge — each once the one before it is
# healthy, which is what `depends_on: condition: service_healthy` does in the compose file.

locals {
  namespace     = aws_service_discovery_private_dns_namespace.till.name
  kafka_brokers = "kafka.${local.namespace}:9092"
  ledger_url    = "http://ledger.${local.namespace}:8080"
  jdbc          = "jdbc:postgresql://${aws_db_instance.till.address}:5432"

  logs = { for name, group in var.log_groups : name => {
    logDriver = "awslogs"
    options = {
      "awslogs-group"         = group
      "awslogs-region"        = var.region
      "awslogs-stream-prefix" = name
    }
  } }

  # Readiness, as in the compose file: it asks the database, which is what serving a request needs.
  # A Spring Boot start on half a vCPU takes the better part of a minute.
  readiness = { interval = 10, timeout = 5, retries = 3, startPeriod = 180 }

  # Who the store's customers sign in with: Cognito, or for a load test the stand-in provider
  # (loadtest.tf), which the load generator reaches over plain HTTP — so the session cookie cannot be
  # Secure, or it would never be sent back. docs/load-test.md lists what else a load test changes.
  identity = var.loadtest ? tomap({
    STORE_OIDC_ISSUER_URI        = local.standin
    STORE_OIDC_CLIENT_ID         = local.standin_client
    STORE_OIDC_AUTHORIZATION_URI = "${local.standin}/authorize"
    STORE_OIDC_TOKEN_URI         = "${local.standin}/token"
    STORE_OIDC_JWK_SET_URI       = "${local.standin}/certs"
    STORE_OIDC_LOGOUT_URI        = ""
    STORE_COOKIE_SECURE          = "false"
    # Every game stocked beyond what a run can buy, so no checkout is refused for want of stock.
    SPRING_APPLICATION_JSON = jsonencode({ store = { demo = { "default-stock" = 1000000000 } } })
    }) : tomap({
    # Against Cognito the issuer is enough: everything else is in its discovery document.
    STORE_OIDC_ISSUER_URI = "https://cognito-idp.${var.region}.amazonaws.com/${var.user_pool.id}"
    STORE_OIDC_CLIENT_ID  = aws_cognito_user_pool_client.store[0].id
    STORE_OIDC_LOGOUT_URI = "https://${var.user_pool.domain}.auth.${var.region}.amazoncognito.com/logout?client_id={clientId}&logout_uri={baseUrl}/"
    STORE_COOKIE_SECURE   = "true"
    # The compose stack's handful of nearly-sold-out games, so the page shows what "only 3 left" and
    # "sold out" look like here too.
    SPRING_APPLICATION_JSON = jsonencode({
      store = { demo = { stock = { ninefold = 3, "hollow-meridian" = 2, "tin-soldier-hop" = 4, "ashen-crown" = 0 } } }
    })
  })
  identity_secret = var.loadtest ? aws_ssm_parameter.standin_secret[0].arn : aws_ssm_parameter.oidc_client_secret[0].arn
}

resource "aws_ecs_cluster" "till" {
  name = "till"

  # Container Insights is billed per metric, and each service already publishes its own on a
  # Prometheus endpoint.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

# --- kafka ---------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "kafka" {
  family                   = "till-kafka"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.size.kafka.cpu
  memory                   = var.size.kafka.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = "kafka"
    image        = var.images.kafka
    essential    = true
    portMappings = [{ containerPort = 9092, protocol = "tcp" }]

    # docker-compose.yml's broker, advertising the name the others resolve. Its log is the task's
    # own disk: the outbox is the durable record, and a broker that restarts empty is sent whatever
    # has not been published yet.
    environment = [for name, value in {
      KAFKA_NODE_ID                                  = "1"
      KAFKA_PROCESS_ROLES                            = "broker,controller"
      KAFKA_LISTENERS                                = "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS                     = "PLAINTEXT://${local.kafka_brokers}"
      KAFKA_CONTROLLER_QUORUM_VOTERS                 = "1@localhost:9093"
      KAFKA_CONTROLLER_LISTENER_NAMES                = "CONTROLLER"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP           = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR         = "1"
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "1"
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR            = "1"
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS         = "0"
      KAFKA_AUTO_CREATE_TOPICS_ENABLE                = "true"
    } : { name = name, value = value }]

    # The compose file's check: the broker must list its topics, not merely listen.
    healthCheck = {
      command     = ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list > /dev/null 2>&1"]
      interval    = 15
      timeout     = 10
      retries     = 5
      startPeriod = 60
    }

    logConfiguration = local.logs.kafka
  }])
}

resource "aws_ecs_service" "kafka" {
  name            = "kafka"
  cluster         = aws_ecs_cluster.till.id
  task_definition = aws_ecs_task_definition.kafka.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # One broker, which must never briefly be two: they would share a node id.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [var.security_groups.kafka]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service["kafka"].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true
  propagate_tags         = "SERVICE"
  wait_for_steady_state  = true
}

# --- the ledger ----------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "ledger" {
  family                   = "till-ledger"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.size.ledger.cpu
  memory                   = var.size.ledger.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = "ledger"
    image        = var.images.runtime
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    environment = [for name, value in {
      # RDS for PostgreSQL 17 refuses a connection without TLS.
      TILL_DB_URL        = "${local.jdbc}/till?sslmode=require"
      TILL_DB_USER       = "till"
      TILL_DB_POOL       = tostring(var.db_pool.ledger)
      TILL_KAFKA_BROKERS = local.kafka_brokers
    } : { name = name, value = value }]

    secrets = [for name, arn in {
      TILL_DB_PASSWORD  = var.secrets.db_password
      TILL_CLIENT_TOKEN = var.secrets.ledger_client_token
      TILL_ADMIN_TOKEN  = var.secrets.ledger_admin_token
    } : { name = name, valueFrom = arn }]

    healthCheck = merge(local.readiness, {
      command = ["CMD", "curl", "-fsS", "http://127.0.0.1:9101/actuator/health/readiness"]
    })

    logConfiguration = local.logs.ledger
  }])
}

resource "aws_ecs_service" "ledger" {
  name            = "ledger"
  cluster         = aws_ecs_cluster.till.id
  task_definition = aws_ecs_task_definition.ledger.arn
  desired_count   = var.size.ledger.count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [var.security_groups.ledger]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service["ledger"].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true
  propagate_tags         = "SERVICE"
  wait_for_steady_state  = true

  depends_on = [aws_ecs_service.kafka]
}

# --- the store -----------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "store" {
  family                   = "till-store"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.size.store.cpu
  memory                   = var.size.store.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    # What docker/initdb does for the compose stack: the store's own database, beside the ledger's.
    # Every start asks, and creates it only if it is missing; two starting at once cannot both fail,
    # because the loser finds the winner's.
    {
      name      = "create-database"
      image     = var.images.postgres
      essential = false
      command = ["sh", "-c", join(" ", [
        "exists() { psql -tAc \"select 1 from pg_database where datname = 'store'\" | grep -q 1; };",
        "exists || psql -qc 'create database store' || exists",
      ])]
      environment = [for name, value in {
        PGHOST            = aws_db_instance.till.address
        PGUSER            = "till"
        PGDATABASE        = "till"
        PGSSLMODE         = "require"
        PGCONNECT_TIMEOUT = "10"
      } : { name = name, value = value }]
      secrets          = [{ name = "PGPASSWORD", valueFrom = var.secrets.db_password }]
      logConfiguration = local.logs.store
    },
    {
      name         = "store"
      image        = var.images.runtime
      essential    = true
      entryPoint   = ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/till-store.jar"]
      portMappings = [{ containerPort = 8081, protocol = "tcp" }]
      dependsOn    = [{ containerName = "create-database", condition = "SUCCESS" }]

      environment = [for name, value in merge(local.identity, {
        STORE_DB_URL  = "${local.jdbc}/store?sslmode=require"
        STORE_DB_USER = "till"
        STORE_DB_POOL = tostring(var.db_pool.store)

        STORE_REDIS_HOST = aws_elasticache_replication_group.sessions.primary_endpoint_address
        # Spring Boot's own property, as an environment variable: the cache only speaks TLS.
        SPRING_DATA_REDIS_SSL_ENABLED = "true"

        STORE_KAFKA_BROKERS = local.kafka_brokers
        TILL_URL            = local.ledger_url

        STORE_DEMO_SEED_STOCK = tostring(var.demo || var.loadtest)
        STORE_CATALOGUE_CACHE = tostring(var.catalogue_cache)
      }) : { name = name, value = value }]

      secrets = [for name, arn in {
        STORE_DB_PASSWORD        = var.secrets.db_password
        TILL_CLIENT_TOKEN        = var.secrets.ledger_client_token
        TILL_ADMIN_TOKEN         = var.secrets.ledger_admin_token
        STORE_OIDC_CLIENT_SECRET = local.identity_secret
      } : { name = name, valueFrom = arn }]

      healthCheck = merge(local.readiness, {
        command = ["CMD", "curl", "-fsS", "http://127.0.0.1:9102/actuator/health/readiness"]
      })

      logConfiguration = local.logs.store
    },
  ])
}

resource "aws_ecs_service" "store" {
  name            = "store"
  cluster         = aws_ecs_cluster.till.id
  task_definition = aws_ecs_task_definition.store.arn
  desired_count   = var.size.store.count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [var.security_groups.store]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service["store"].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true
  propagate_tags         = "SERVICE"
  wait_for_steady_state  = true

  # The store stocks the demonstration through the ledger as it starts.
  depends_on = [aws_ecs_service.ledger]
}

# --- the edge ------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "edge" {
  family                   = "till-edge"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.size.edge.cpu
  memory                   = var.size.edge.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = "edge"
    image        = var.images.edge
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    # The variables docker/edge's template is rendered from. The VPC's resolver is the address two
    # above its base; the load balancer, the only thing that connects, is somewhere in its range.
    environment = [for name, value in {
      STORE_UPSTREAM     = "store.${local.namespace}:8081"
      EDGE_RESOLVER      = cidrhost(var.vpc_cidr, 2)
      EDGE_TRUSTED_PROXY = var.vpc_cidr
      # Off for a load test: at its rate the log alone would cost more than the edge does.
      EDGE_ACCESS_LOG = var.loadtest ? "off" : "/var/log/nginx/access.log edge"
    } : { name = name, value = value }]

    # What docker/edge/nginx.conf asks for: a descriptor per connection, and 16,384 connections a
    # worker.
    ulimits = [{ name = "nofile", softLimit = 65535, hardLimit = 65535 }]

    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/healthz > /dev/null"]
      interval    = 10
      timeout     = 3
      retries     = 3
      startPeriod = 10
    }

    logConfiguration = local.logs.edge
  }])
}

resource "aws_ecs_service" "edge" {
  name            = "edge"
  cluster         = aws_ecs_cluster.till.id
  task_definition = aws_ecs_task_definition.edge.arn
  desired_count   = var.size.edge.count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [var.security_groups.edge]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.edge.arn
    container_name   = "edge"
    container_port   = 8080
  }

  health_check_grace_period_seconds = 30

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true
  propagate_tags         = "SERVICE"
  wait_for_steady_state  = true

  depends_on = [aws_ecs_service.store, aws_lb_listener.edge]
}
