# What a load test adds (docs/load-test.md), and only while `loadtest` is true: the stand-in OpenID
# provider its shoppers sign in with, running as a service; the load generator's task definition,
# which scripts/aws.sh loadtest runs once per run rather than keeping up; and a one-off psql task that
# asks the database what it spent its time on.

locals {
  standin        = "http://idp.${local.namespace}:8090"
  standin_client = "till-store"

  idp_size     = lookup(var.size, "idp", { cpu = 512, memory = 1024, count = 1 })
  loadgen_size = lookup(var.size, "loadgen", { cpu = 8192, memory = 16384, count = 1 })
}

# The store's secret at the stand-in, which checks it when the store redeems a code, as Cognito does.
resource "random_password" "standin_secret" {
  count = var.loadtest ? 1 : 0

  length  = 32
  special = false
}

resource "aws_ssm_parameter" "standin_secret" {
  count = var.loadtest ? 1 : 0

  name        = "/till/loadtest/client-secret"
  description = "The store's client secret at the load test's stand-in OpenID provider"
  type        = "SecureString"
  value       = random_password.standin_secret[0].result
}

# --- who may reach whom --------------------------------------------------------------------------

# The provider answers the store's token requests and the shoppers' authorization requests, and
# nothing else. The load generator reaches the load balancer and the provider — what a shopper's
# browser reaches in production is CloudFront and Cognito.
resource "aws_security_group" "loadtest" {
  for_each = var.loadtest ? {
    idp     = "The load test stand-in OpenID provider"
    loadgen = "The load test load generator"
    dbstat  = "The load test psql task that reads pg_stat_statements"
  } : {}

  name        = "till-${each.key}"
  description = each.value
  vpc_id      = var.vpc_id

  tags = { Name = "till-${each.key}" }
}

resource "aws_vpc_security_group_ingress_rule" "loadtest" {
  for_each = var.loadtest ? {
    idp_from_store   = { to = aws_security_group.loadtest["idp"].id, from = var.security_groups.store, port = 8090 }
    idp_from_loadgen = { to = aws_security_group.loadtest["idp"].id, from = aws_security_group.loadtest["loadgen"].id, port = 8090 }
    alb_from_loadgen = { to = var.security_groups.alb, from = aws_security_group.loadtest["loadgen"].id, port = 80 }
    db_from_dbstat   = { to = var.security_groups.db, from = aws_security_group.loadtest["dbstat"].id, port = 5432 }
  } : {}

  security_group_id            = each.value.to
  referenced_security_group_id = each.value.from
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = replace(each.key, "_", " ")
}

resource "aws_vpc_security_group_egress_rule" "loadtest" {
  for_each = aws_security_group.loadtest

  security_group_id = each.value.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "ECR, CloudWatch and Parameter Store; and the load balancer and provider"
}

# --- the stand-in provider -----------------------------------------------------------------------

resource "aws_service_discovery_service" "idp" {
  count = var.loadtest ? 1 : 0

  name          = "idp"
  force_destroy = true

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.till.id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  health_check_custom_config {}

  # As for the other services: the provider does not read this block back (discovery.tf).
  lifecycle {
    ignore_changes = [health_check_custom_config]
  }
}

resource "aws_ecs_task_definition" "idp" {
  count = var.loadtest ? 1 : 0

  family                   = "till-idp"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.idp_size.cpu
  memory                   = local.idp_size.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name         = "idp"
    image        = var.images.loadtest
    essential    = true
    entryPoint   = ["java", "-jar", "/loadtest/till-loadtest.jar"]
    portMappings = [{ containerPort = 8090, protocol = "tcp" }]

    environment = [for name, value in {
      STANDIN_ISSUER    = local.standin
      STANDIN_CLIENT_ID = local.standin_client
    } : { name = name, value = value }]
    secrets = [{ name = "STANDIN_CLIENT_SECRET", valueFrom = aws_ssm_parameter.standin_secret[0].arn }]

    # The image has no curl; bash, by name, can open a socket on its own.
    healthCheck = {
      command = ["CMD", "bash", "-c", join(" ", [
        "exec 3<>/dev/tcp/127.0.0.1/8090 &&",
        "printf 'GET /health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 &&",
        "grep -q '^ok' <&3",
      ])]
      interval    = 10
      timeout     = 5
      retries     = 3
      startPeriod = 20
    }

    logConfiguration = local.logs.loadtest
  }])
}

resource "aws_ecs_service" "idp" {
  count = var.loadtest ? 1 : 0

  name            = "idp"
  cluster         = aws_ecs_cluster.till.id
  task_definition = aws_ecs_task_definition.idp[0].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.task_subnet_ids
    security_groups  = [aws_security_group.loadtest["idp"].id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.idp[0].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  propagate_tags        = "SERVICE"
  wait_for_steady_state = true
}

# --- the load generator --------------------------------------------------------------------------

# A task definition and not a service: each run is one task, started by scripts/aws.sh loadtest with
# the run's shoppers and durations, which exits when the run is over.
resource "aws_ecs_task_definition" "loadgen" {
  count = var.loadtest ? 1 : 0

  family                   = "till-loadgen"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.loadgen_size.cpu
  memory                   = local.loadgen_size.memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "loadgen"
    image     = var.images.loadtest
    essential = true
    command   = ["run", "/loadtest/k6/shopper.js"]

    # The protocol's run. scripts/aws.sh loadtest overrides these for a shorter one.
    environment = [for name, value in {
      BASE_URL  = "http://${aws_lb.edge.dns_name}"
      SHOPPERS  = "8000"
      RAMP      = "5m"
      HOLD      = "10m"
      RAMP_DOWN = "30s"
    } : { name = name, value = value }]

    # A connection or two per shopper, eight thousand shoppers.
    ulimits = [{ name = "nofile", softLimit = 65535, hardLimit = 65535 }]

    logConfiguration = local.logs.loadtest
  }])
}

# --- what the database spent its time on ----------------------------------------------------------

# One psql statement per task, the statement in SQL: scripts/aws.sh loadtest resets
# pg_stat_statements before a run and reads the most expensive statements after it. Both RDS and
# Aurora PostgreSQL preload the module by default; the extension is created on first use.
resource "aws_ecs_task_definition" "dbstat" {
  count = var.loadtest ? 1 : 0

  family                   = "till-dbstat"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "dbstat"
    image     = var.images.postgres
    essential = true
    # Unaligned and tuples only: what it prints is the statement's one value, which is JSON.
    command = ["sh", "-c", "psql -X -q -v ON_ERROR_STOP=1 -P pager=off -A -t -c \"$SQL\""]

    environment = [for name, value in {
      PGHOST            = local.db_endpoint
      PGUSER            = "till"
      PGDATABASE        = "till"
      PGSSLMODE         = "require"
      PGCONNECT_TIMEOUT = "10"
      SQL               = "select 1"
    } : { name = name, value = value }]
    secrets = [{ name = "PGPASSWORD", valueFrom = var.secrets.db_password }]

    logConfiguration = local.logs.loadtest
  }])
}
