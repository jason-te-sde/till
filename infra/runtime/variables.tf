variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "zone" {
  description = "The availability zone for everything single-instance: the database, the cache, the containers."
  type        = string
}

variable "task_subnet_ids" {
  description = "Where the containers run: public subnets, for egress without a NAT gateway."
  type        = list(string)
}

variable "alb_subnet_ids" {
  description = "Where the internal load balancer lives: private subnets in two zones, which a load balancer requires."
  type        = list(string)
}

variable "security_groups" {
  description = "A security group per tier: alb, edge, store, ledger, kafka, db, cache."
  type        = map(string)
}

variable "db_subnet_group" {
  type = string
}

variable "cache_subnet_group" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "log_groups" {
  description = "A CloudWatch log group per service: edge, store, ledger, kafka."
  type        = map(string)
}

variable "images" {
  type = object({
    runtime  = string
    edge     = string
    kafka    = string
    postgres = string
    loadtest = string
  })
}

variable "secrets" {
  description = "Parameter Store ARNs, which ECS resolves into the containers' environment."
  type = object({
    ledger_client_token = string
    ledger_admin_token  = string
    db_password         = string
  })
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "user_pool" {
  type = object({
    id     = string
    domain = string
  })
}

variable "size" {
  type = map(object({
    cpu    = number
    memory = number
    count  = optional(number, 1)
  }))
}

variable "db_instance_class" {
  type = string
}

variable "database" {
  description = "Which PostgreSQL runs: \"rds\", \"aurora\" or \"aurora-express\" (state.tf)."
  type        = string
}

variable "express_clusters" {
  description = "With database = \"aurora-express\": the clusters scripts/aws.sh created, \"till\" and \"store\", each its writer endpoint and resource id."
  type = map(object({
    endpoint    = string
    resource_id = string
  }))
}

variable "database_per_service" {
  description = "Whether the store gets a PostgreSQL server of its own, \"till-store\" (state.tf), instead of sharing the ledger's."
  type        = bool
}

variable "cache_node_type" {
  type = string
}

variable "demo" {
  type = bool
}

variable "auto_stop_role_arn" {
  description = "The role EventBridge Scheduler scales the services to zero with (roles.tf)."
  type        = string
}

variable "auto_stop_hours" {
  type = number
}

variable "db_pool" {
  type = object({
    store  = number
    ledger = number
  })
}

variable "catalogue_cache" {
  type = bool
}

variable "stock_shards" {
  type = number
}

variable "loadtest" {
  description = "Set up for a load test (docs/load-test.md) rather than for customers: no CloudFront, the stand-in provider, the load generator."
  type        = bool
}
