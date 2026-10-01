# Where the state lives: PostgreSQL, for the ledger's database and the store's, and one Valkey node
# for the store's sessions.
#
# PostgreSQL is RDS or Aurora PostgreSQL Serverless v2, picked per deployment by var.database
# (infra/variables.tf; default "rds", unchanged). The AWS free plan caps each differently: RDS to
# db.t3.micro or db.t4g.micro, Aurora to 4 ACU and 1 GiB of storage per cluster — infra/README.md has
# what both cost. Exactly one of the two resources below exists at a time (count); local.db_endpoint
# is what the ledger connects to either way, and both are identified "till" so a CloudWatch query
# (scripts/aws.sh server_side) never has to know which one is live.
#
# var.database_per_service (infra/variables.tf; default false, unchanged) gives the store a second
# server of the same kind, "till-store", below — the free plan caps an instance's class, not how many
# instances exist, so this is the lever once one server is the shared bottleneck. local.store_db_endpoint
# is what services.tf's store connects to: the store's own server when per-service, otherwise
# local.db_endpoint, the same server the ledger is on. The ledger always stays on local.db_endpoint.
#
# Both single-instance, no replicas, no backups beyond what the engine insists on: this deployment
# is started for a session and destroyed after it, and the store stocks itself on start. What a
# production deployment would change is in infra/README.md.

resource "aws_db_instance" "till" {
  count = var.database == "rds" ? 1 : 0

  identifier     = "till"
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  # The ledger's database. The store's is created by the store's task before it starts (services.tf),
  # because RDS makes only one.
  db_name  = "till"
  username = "till"
  password = var.db_password

  db_subnet_group_name   = var.db_subnet_group
  vpc_security_group_ids = [var.security_groups.db]
  availability_zone      = var.zone
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period      = 0
  skip_final_snapshot          = true
  deletion_protection          = false
  apply_immediately            = true
  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

# count alone would otherwise replace this instance: Terraform addresses a counted resource as
# till[0], not till, and without this move the next plan would destroy and recreate the one a
# running deployment already has state for.
moved {
  from = aws_db_instance.till
  to   = aws_db_instance.till[0]
}

# Aurora Serverless v2. engine_mode = "provisioned" is what a cluster made of Serverless v2
# instances is called — "serverless" (no v2) is the first, retired generation, and takes neither
# var.db_instance_class nor the scaling block below.
#
# 17.9 is the closest Aurora release to the RDS instance's PostgreSQL 17, and is confirmed to exist:
# https://aws.amazon.com/about-aws/whats-new/2026/04/amazon-aurora-postgresql-17-9-16-13-15-17-14-22
# Later 17.x minors exist by now (up to 17.11, announced September 2026) — auto_minor_version_upgrade
# on the instance below tracks them forward, as it already does for the RDS instance above. Before
# the first real apply, check `aws rds describe-db-engine-versions --engine aurora-postgresql
# --region <region>` in case 17.9 has aged out of the region's offering by then.
#
# min_capacity = 0 pauses the instance between connections instead of floating at a minimum charge.
# Aurora PostgreSQL has supported a minimum of 0 ACU since versions 13.15, 14.12, 15.7 and 16.3 — all
# older than 17, so 17.9 qualifies:
# https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-serverless-v2-auto-pause.html
# A paused instance takes about fifteen seconds to resume on the next connection, far longer than the
# services wait for one (two seconds), but it does not pause while they run: it pauses only after
# five minutes with no connection at all, and their pools keep theirs open. What it saves is the
# cluster nobody is connected to.
resource "aws_rds_cluster" "till" {
  count = var.database == "aurora" ? 1 : 0

  cluster_identifier = "till"
  engine             = "aurora-postgresql"
  engine_version     = "17.9"
  engine_mode        = "provisioned"

  # The ledger's database. The store's is created by its task before it starts, exactly as for RDS
  # (services.tf) — Aurora, like RDS, makes only the one named here.
  database_name   = "till"
  master_username = "till"
  master_password = var.db_password

  # No availability_zones. Aurora keeps a cluster's storage in three zones whatever is named here,
  # and a list of fewer comes back as three, which Terraform reads as a change that replaces the
  # cluster — at the next apply, and every one after. The instance below is what is put in
  # var.zone, next to the tasks.
  db_subnet_group_name   = var.db_subnet_group
  vpc_security_group_ids = [var.security_groups.db]
  storage_encrypted      = true

  serverlessv2_scaling_configuration {
    min_capacity = 0
    max_capacity = 4
  }

  # Aurora's API refuses 0 here, unlike a plain RDS instance's: 1 day is its minimum.
  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true

  # pg_stat_statements, which the load test reads (scripts/aws.sh dbstat), is already in the default
  # Aurora PostgreSQL cluster parameter group's shared_preload_libraries, so no custom one is needed:
  # "Typically, the default DB cluster parameter group loads only the pg_stat_statements."
  # https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/Appendix.PostgreSQL.CommonDBATasks.html
}

resource "aws_rds_cluster_instance" "till" {
  count = var.database == "aurora" ? 1 : 0

  identifier         = "till"
  cluster_identifier = aws_rds_cluster.till[0].id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.till[0].engine
  engine_version     = aws_rds_cluster.till[0].engine_version

  availability_zone            = var.zone
  publicly_accessible          = false
  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

locals {
  # For Aurora, the instance's address rather than the cluster's. The cluster is ready before it has
  # an instance to answer, and the services wait only for what they refer to: given the cluster's,
  # they would start against an endpoint with nothing behind it. With one instance, its address is
  # the writer's.
  db_endpoint = var.database == "aurora" ? aws_rds_cluster_instance.till[0].endpoint : aws_db_instance.till[0].address
}

# --- the store's own server, when database_per_service asks for one ------------------------------
#
# The same engine, class or scaling, and settings as the ledger's pair above — db_name "till" rather
# than "store" included, mirroring them on purpose rather than naming this one for what it actually
# holds. services.tf's create-database init container connects to "postgres" (always present,
# whatever a server's initial database is named) to check for and create "store", so it works
# unchanged whichever server it is pointed at, and nothing here has to special-case which database a
# fresh server was given. "till-store" is a resource of its own, not a rename of the resources
# above: var.database_per_service does not change what the ledger is on.
resource "aws_db_instance" "store" {
  count = var.database == "rds" && var.database_per_service ? 1 : 0

  identifier     = "till-store"
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "till"
  username = "till"
  password = var.db_password

  db_subnet_group_name   = var.db_subnet_group
  vpc_security_group_ids = [var.security_groups.db]
  availability_zone      = var.zone
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period      = 0
  skip_final_snapshot          = true
  deletion_protection          = false
  apply_immediately            = true
  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

# Aurora's free-plan cap (4 ACU, 1 GiB of storage) is per cluster, so a second cluster at the same
# 0-4 ACU scaling as the ledger's does not share or halve either server's ceiling.
resource "aws_rds_cluster" "store" {
  count = var.database == "aurora" && var.database_per_service ? 1 : 0

  cluster_identifier = "till-store"
  engine             = "aurora-postgresql"
  engine_version     = "17.9"
  engine_mode        = "provisioned"

  database_name   = "till"
  master_username = "till"
  master_password = var.db_password

  # No availability_zones: the same reason as the ledger's cluster above.
  db_subnet_group_name   = var.db_subnet_group
  vpc_security_group_ids = [var.security_groups.db]
  storage_encrypted      = true

  serverlessv2_scaling_configuration {
    min_capacity = 0
    max_capacity = 4
  }

  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}

resource "aws_rds_cluster_instance" "store" {
  count = var.database == "aurora" && var.database_per_service ? 1 : 0

  identifier         = "till-store"
  cluster_identifier = aws_rds_cluster.store[0].id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.store[0].engine
  engine_version     = aws_rds_cluster.store[0].engine_version

  availability_zone            = var.zone
  publicly_accessible          = false
  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
}

locals {
  # The store's own server once it has one; otherwise the one the ledger is already on. services.tf's
  # store connects here; the ledger always stays on local.db_endpoint above, whichever this is.
  store_db_endpoint = var.database_per_service ? (
    var.database == "aurora" ? aws_rds_cluster_instance.store[0].endpoint : aws_db_instance.store[0].address
  ) : local.db_endpoint
}

resource "aws_elasticache_replication_group" "sessions" {
  replication_group_id = "till-sessions"
  description          = "Sessions for the store"

  engine               = "valkey"
  engine_version       = "8.2"
  parameter_group_name = "default.valkey8"
  node_type            = var.cache_node_type
  port                 = 6379

  num_cache_clusters          = 1
  automatic_failover_enabled  = false
  multi_az_enabled            = false
  preferred_cache_cluster_azs = [var.zone]

  subnet_group_name  = var.cache_subnet_group
  security_group_ids = [var.security_groups.cache]

  # A session is a signed-in customer's tokens.
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true

  snapshot_retention_limit = 0
  apply_immediately        = true
}
