variable "region" {
  description = "Where everything runs. The bootstrap stack must have been applied in the same one."
  type        = string
  default     = "us-west-2"
}

variable "running" {
  description = <<-EOT
    Whether the half billed by the hour exists: the load balancer, CloudFront, the database, the
    cache and the containers. false is the stopped state and the default, so an apply that forgets
    to say is one that stops the bill rather than one that starts it.
  EOT
  type        = bool
  default     = false
}

variable "image_tag" {
  description = "The tag of till/runtime and till/edge to run. scripts/aws.sh sets it to the commit it built and pushed."
  type        = string
  default     = ""

  validation {
    condition     = !var.running || var.image_tag != ""
    error_message = "running = true needs image_tag: the commit whose images were pushed."
  }
}

variable "kafka_version" {
  description = "The apache/kafka image mirrored into till/kafka. The same broker as docker-compose.yml."
  type        = string
  default     = "4.1.0"
}

variable "vpc_cidr" {
  description = "The VPC's range. The edge trusts connections from it to say who the client is."
  type        = string
  default     = "10.40.0.0/16"
}

variable "size" {
  description = <<-EOT
    Fargate CPU units and MiB for each task, and how many of each to run. Kafka always runs three
    nodes (runtime/services.tf) whatever `count` says here, one per broker: this is the size of
    each one, not a total, and `count` on this entry is ignored.
  EOT
  type = map(object({
    cpu    = number
    memory = number
    count  = optional(number, 1)
  }))
  default = {
    edge   = { cpu = 256, memory = 512 }
    store  = { cpu = 512, memory = 2048 }
    ledger = { cpu = 512, memory = 2048 }
    # 2 GiB for Kafka's default 1 GiB heap, with room for the health check's CLI beside it. Per
    # broker: three of these run, so the running cost is this size times three (infra/README.md).
    kafka = { cpu = 512, memory = 2048 }
  }

  validation {
    condition     = alltrue([for name in ["edge", "store", "ledger", "kafka"] : contains(keys(var.size), name)])
    error_message = "size needs an entry for each of edge, store, ledger and kafka."
  }
}

variable "db_pool" {
  description = <<-EOT
    Connections each store and each ledger may hold. All of them together have to stay under what
    the database allows — about a hundred for a db.t4g.micro — and a few more than its cores is what
    keeps it busiest. Each budget is its own server's when database_per_service is true: the store's
    pool no longer shares the ledger's connection limit, or its CPUs.
  EOT
  type = object({
    store  = number
    ledger = number
  })
  default = { store = 16, ledger = 16 }
}

variable "db_instance_class" {
  description = "The RDS instance class. db.t4g.micro is the smallest PostgreSQL 17 runs on."
  type        = string
  default     = "db.t4g.micro"
}

variable "database" {
  description = <<-EOT
    Which PostgreSQL runs: "rds" (an aws_db_instance; the free plan allows only db.t3.micro or
    db.t4g.micro) or "aurora" (an Aurora PostgreSQL Serverless v2 cluster; the free plan caps it at
    4 ACU and 1 GiB of storage per cluster). infra/README.md has what each costs.
    scripts/aws.sh up --database=aurora sets it; the default is unchanged.
  EOT
  type        = string
  default     = "rds"

  validation {
    condition     = contains(["rds", "aurora"], var.database)
    error_message = "database is either \"rds\" or \"aurora\"."
  }
}

variable "database_per_service" {
  description = <<-EOT
    Whether the store gets a PostgreSQL server of its own ("till-store") instead of sharing the
    ledger's. Today both databases, till's and store's, live on one server (infra/runtime/state.tf);
    on the free plan that is a db.t4g.micro, the biggest RDS instance class the account may create,
    and in the last load test it was at 98% CPU — about three quarters of the statement time the
    ledger's, the rest the store's — with one connection budget (db_pool, about 70 total) shared by
    both services' pools. The free plan caps an instance's *class*, not how many instances exist, so
    a second server gives the store its own CPUs and its own connection budget. false (the default,
    unchanged) keeps today's one-server layout. infra/README.md has what a second server costs.
    scripts/aws.sh up --database-per-service sets it; switching a running deployment is down, then
    up (scripts/aws.sh's same_layout guard refuses to switch a layout underneath one, the same way
    same_database already refuses to switch rds/aurora underneath one).
  EOT
  type        = bool
  default     = false
}

variable "cache_node_type" {
  description = "The ElastiCache node type for the store's sessions."
  type        = string
  default     = "cache.t4g.micro"
}

variable "loadtest" {
  description = <<-EOT
    Set the running half up for a load test (docs/load-test.md) instead of for customers: no
    CloudFront, sign-in against the stand-in provider, and the load generator's task definition.
    scripts/aws.sh up --loadtest sets it, with the sizes in loadtest.tfvars.
  EOT
  type        = bool
  default     = false

  validation {
    condition     = !var.loadtest || var.running
    error_message = "loadtest = true needs running = true: it is a way of running."
  }
}

variable "auto_stop_hours" {
  description = <<-EOT
    How long after the last `scripts/aws.sh up` the safety net scales every service to zero
    (runtime/auto-stop.tf), in case nobody is left to run `down`. Each `up` starts the clock again.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.auto_stop_hours >= 1 && var.auto_stop_hours <= 24
    error_message = "auto_stop_hours is between 1 and 24."
  }
}

variable "catalogue_cache" {
  description = "Whether the store caches catalogue answers in Valkey. Off only to measure what it is worth (docs/load-test.md)."
  type        = bool
  default     = true
}

variable "stock_shards" {
  description = "How many rows the ledger keeps each demonstration game's stock in once the store has stocked it (docs/design/0009-hot-sku-shards.md). 1 leaves every game in one row."
  type        = number
  default     = 1

  validation {
    condition     = var.stock_shards >= 1 && var.stock_shards <= 64
    error_message = "stock_shards is between 1 and 64."
  }
}

variable "demo" {
  description = "Stock every game when the store starts, as the compose stack does. A real store must not."
  type        = bool
  default     = true
}
