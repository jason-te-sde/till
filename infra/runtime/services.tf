# The four services of docker-compose.yml, on Fargate: the same images, the same environment, with
# Cognito for Keycloak, RDS for postgres and ElastiCache for redis. ARM64, which Fargate bills about
# a fifth below x86, and which is what the images are built as on the machine that pushes them.
#
# Kafka is three of these services, not one — docker-compose.kafka-cluster.yml is the local
# equivalent; see the comment above its task definition for why. They start together; the ledger
# starts once all three are healthy, the store once the ledger is, the edge once the store is,
# which is what `depends_on: condition: service_healthy` does in the compose file.

locals {
  namespace = aws_service_discovery_private_dns_namespace.till.name

  # Three KRaft nodes, broker and controller together, each its own ECS service so it keeps a
  # stable node id and a stable Cloud Map name across a replacement: kafka-2 is always kafka-2,
  # whichever task is currently running under that name. Strings throughout rather than numbers,
  # because for_each needs a string key for the name regardless, and a local that is a number in
  # one place and a string in another is harder to follow than one that only ever is a string.
  kafka_ids  = ["1", "2", "3"]
  kafka_host = { for id in local.kafka_ids : id => "kafka-${id}.${local.namespace}" }

  # The ledger and the store get every broker, not just one: a bootstrap list is only where a
  # client connects first, and listing all three means that first connection does not depend on
  # which one happens to be up.
  kafka_brokers = join(",", [for id in local.kafka_ids : "${local.kafka_host[id]}:9092"])

  # Every node's view of the controller quorum, identical on all three. Unlike the single-node
  # setup this replaces, a voter cannot be `localhost`: each node dials the *other* two over the
  # network, at the Cloud Map name that follows whichever task is currently running under it.
  kafka_voters = join(",", [for id in local.kafka_ids : "${id}@${local.kafka_host[id]}:9093"])

  ledger_url = "http://ledger.${local.namespace}:8080"
  # local.db_endpoint (state.tf): RDS or Aurora, picked per deployment (var.database) — Kafka does
  # not care which; it only needs an address to put after the scheme.
  jdbc = "jdbc:postgresql://${local.db_endpoint}:5432"

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

# --- kafka -----------------------------------------------------------------------------------------
#
# Three nodes, each broker and controller together, so the topic the ledger declares — replication
# factor 3, min.insync.replicas 2 below — can lose any one of them and still take an acknowledged
# write. The offsets and transaction-log settings give Kafka's own internal topics the same 3-and-2
# shape, so the cluster's bookkeeping has the guarantee the outbox does.
#
# One shared cluster id, generated once (below) rather than left unset: KRaft refuses to let a node
# join a quorum under a different cluster id than the one already running, and each node formats
# its own (empty, ephemeral) storage with whichever id it is given on first boot. Left unset, all
# three would fall back to a value baked into the image — the same value for all three, so it would
# even happen to work, but by accident of the base image rather than by a decision this reads.
#
# Fargate's disk is ephemeral: a replacement — a deployment, an OOM kill, a host Fargate retires —
# comes back empty, both for the partition data a node held and for its share of the controller
# quorum's metadata log. For partition data that is ordinary Kafka: the empty node rejoins out of
# the in-sync set and re-replicates from the two that still have it, and min.insync.replicas=2 keeps
# acknowledging writes throughout, because the other two are enough. The controller quorum works
# the same way one level down — the rejoining node's metadata log is behind, and it catches up from
# the current Raft leader as long as a majority of the three (two) stay reachable, which a
# deployment that replaces one node at a time (deployment_minimum_healthy_percent below) never
# threatens by itself. Only two of the three losing their disk at once would stall it.
#
# That is accepted here rather than engineered around with persistent storage (EFS, or MSK),
# because this deployment is a session, not a fixture (infra/README.md): the worst case is a stale
# `available` count until re-replication finishes, or, for the correlated loss above, restarting the
# stack — and the ledger stays authoritative throughout, since nothing downstream can oversell no
# matter what the storefront's projection currently shows (docker-compose.yml's comment says why).
# Three brokers protect against losing one task. They do not protect against losing the zone they
# all run in, which is the same zone the database and the cache are in ("One zone for everything
# with state" below) — a cost trade-off made on purpose, not an oversight.
resource "aws_ecs_task_definition" "kafka" {
  for_each                 = toset(local.kafka_ids)
  family                   = "till-kafka-${each.key}"
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
    name      = "kafka"
    image     = var.images.kafka
    essential = true
    portMappings = [
      { containerPort = 9092, protocol = "tcp" }, # clients, and the other two brokers' data fetch
      { containerPort = 9093, protocol = "tcp" }, # the controller quorum, between brokers only
    ]

    # docker-compose.kafka-cluster.yml's brokers, advertising the name the others resolve. This
    # node's own id and advertised address; the cluster id, the quorum and the replication settings
    # are the same on all three.
    environment = [for name, value in {
      CLUSTER_ID                                     = random_id.kafka_cluster.b64_url
      KAFKA_NODE_ID                                  = each.key
      KAFKA_PROCESS_ROLES                            = "broker,controller"
      KAFKA_LISTENERS                                = "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS                     = "PLAINTEXT://${local.kafka_host[each.key]}:9092"
      KAFKA_CONTROLLER_QUORUM_VOTERS                 = local.kafka_voters
      KAFKA_CONTROLLER_LISTENER_NAMES                = "CONTROLLER"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP           = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
      KAFKA_DEFAULT_REPLICATION_FACTOR               = "3"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR         = "3"
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "3"
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR            = "2"
      KAFKA_MIN_INSYNC_REPLICAS                      = "2"
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS         = "0"
      KAFKA_AUTO_CREATE_TOPICS_ENABLE                = "true"
    } : { name = name, value = value }]

    # The compose file's check: this node must list its topics, not merely listen.
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

# One id shared by all three nodes. A separate resource rather than a literal because Terraform,
# not a human, has to be the one who agrees with itself across three container definitions.
resource "random_id" "kafka_cluster" {
  byte_length = 16
}

resource "aws_ecs_service" "kafka" {
  for_each        = aws_ecs_task_definition.kafka
  name            = "kafka-${each.key}"
  cluster         = aws_ecs_cluster.till.id
  task_definition = each.value.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # One node per service, which must never briefly be two: they would share a node id and collide
  # over the same voter entry.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [var.security_groups.kafka]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.service["kafka-${each.key}"].arn
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
      # Matches the cluster it is talking to: three brokers (above), so the topic this declares —
      # till.kafka.partitions, TillProperties.Kafka — is created with three copies of each
      # partition rather than the application default of one.
      TILL_KAFKA_REPLICATION_FACTOR = "3"
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

  # The store and the ledger hold 64 connections between them at steady state (32 each: see the store
  # service below), which is most of what a db.t4g.micro allows before it refuses the rest — around
  # 70. The default deployment would double the ledger's own 32 to 64 while rolling, the same way it
  # did for the store; replacing one task at a time keeps it at 32 throughout, whatever the count. The
  # minimum is derived rather than a number tied to the load test's two, because this module also runs
  # the default deployment's single task (var.size.ledger.count defaults to 1 in variables.tf, where
  # loadtest.tfvars is not loaded): a hard-coded 50% would still round up to a minimum of 1 against a
  # maximum of 1, and the service could never start a replacement. For one task the derived minimum is
  # 0%, so a deploy stops it and then starts the replacement — a brief gap rather than a stuck one.
  deployment_minimum_healthy_percent = floor(100 * (var.size.ledger.count - 1) / var.size.ledger.count)
  deployment_maximum_percent         = 100
  availability_zone_rebalancing      = "DISABLED"

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

  # A bare reference to a for_each resource depends on every instance of it: all three brokers.
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
        PGHOST            = local.db_endpoint
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
        STORE_DEMO_SHARDS     = tostring(var.stock_shards)
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

  # 4 tasks at 8 connections each (loadtest.tfvars) hold 32 at steady state; the ledger's own 32 bring
  # the total to 64, most of what a db.t4g.micro allows before it refuses the rest with "remaining
  # connection slots are reserved for roles with privileges of the rds_reserved role" — observed
  # refusing around 70. The default deployment (maximumPercent 200) starts four replacement tasks
  # before stopping the four old ones, so a store deploy alone doubled the store's 32 to 64 and pushed
  # the combined total past the limit; that rolled the store back twice on 2026-10-01 when only
  # STORE_DEMO_SHARDS changed. Replacing one task at a time keeps the store at 32 throughout, whatever
  # the count. The minimum is derived rather than a number tied to the load test's four, because this
  # module also runs the default deployment's single task (var.size.store.count defaults to 1 in
  # variables.tf, where loadtest.tfvars is not loaded): a hard-coded 75% would still round up to a
  # minimum of 1 against a maximum of 1, and the service could never start a replacement. For one
  # task the derived minimum is 0%, so a deploy stops it and then starts the replacement — a brief gap
  # rather than a stuck one. Availability Zone Rebalancing is off because ECS refuses maximumPercent
  # of 100 or less otherwise: "Availability Zone Rebalancing does not support maximumPercent <= 100 %".
  deployment_minimum_healthy_percent = floor(100 * (var.size.store.count - 1) / var.size.store.count)
  deployment_maximum_percent         = 100
  availability_zone_rebalancing      = "DISABLED"

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
