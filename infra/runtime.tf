# The half billed by the hour — about $0.16 of it; infra/README.md has the arithmetic. It exists
# while `running` is true and not at all otherwise, so stopping is an apply, not a separate stack
# that has to be kept in step with this one.

locals {
  registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

module "runtime" {
  source = "./runtime"
  count  = var.running ? 1 : 0

  region   = var.region
  vpc_id   = aws_vpc.till.id
  vpc_cidr = var.vpc_cidr

  # Everything that holds state is single-instance, so the containers sit in the same zone as the
  # database and the cache rather than paying for every query to cross one.
  zone            = local.azs[0]
  task_subnet_ids = [aws_subnet.public[0].id]
  alb_subnet_ids  = aws_subnet.private[*].id

  security_groups    = { for tier, group in aws_security_group.tier : tier => group.id }
  db_subnet_group    = aws_db_subnet_group.till.name
  cache_subnet_group = aws_elasticache_subnet_group.till.name

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn
  log_groups         = { for name, group in aws_cloudwatch_log_group.service : name => group.name }

  images = {
    runtime  = "${local.registry}/till/runtime:${var.image_tag}"
    edge     = "${local.registry}/till/edge:${var.image_tag}"
    kafka    = "${local.registry}/till/kafka:${var.kafka_version}"
    loadtest = "${local.registry}/till/loadtest:${var.image_tag}"
    # For one psql command before the store starts. Docker's official image, from ECR's public
    # mirror of it rather than Docker Hub.
    postgres = "public.ecr.aws/docker/library/postgres:17-alpine"
  }

  secrets = {
    ledger_client_token = aws_ssm_parameter.ledger_client_token.arn
    ledger_admin_token  = aws_ssm_parameter.ledger_admin_token.arn
    db_password         = aws_ssm_parameter.db_password.arn
  }
  db_password = random_password.db.result

  user_pool = {
    id     = aws_cognito_user_pool.store.id
    domain = aws_cognito_user_pool_domain.store.domain
  }

  size              = var.size
  db_instance_class = var.db_instance_class
  cache_node_type   = var.cache_node_type
  demo              = var.demo
  loadtest          = var.loadtest
  catalogue_cache   = var.catalogue_cache
  stock_shards      = var.stock_shards
  db_pool           = var.db_pool

  auto_stop_role_arn = aws_iam_role.auto_stop.arn
  auto_stop_hours    = var.auto_stop_hours
}
