# Where the state lives: one PostgreSQL instance with the ledger's database and the store's, as in
# the compose stack, and one Valkey node for the store's sessions.
#
# Both single-instance, no replicas, no backups: this deployment is started for a session and
# destroyed after it, and the store stocks itself on start. What a production deployment would
# change is in infra/README.md.

resource "aws_db_instance" "till" {
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

resource "aws_elasticache_replication_group" "sessions" {
  replication_group_id = "till-sessions"
  description          = "The store's sessions"

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
