output "url" {
  value = local.base_url
}

output "cluster" {
  value = aws_ecs_cluster.till.name
}

output "services" {
  value = concat(
    [for service in aws_ecs_service.kafka : service.name],
    [for service in [aws_ecs_service.ledger, aws_ecs_service.store, aws_ecs_service.edge] : service.name],
  )
}

output "distribution_id" {
  value = one(aws_cloudfront_distribution.till[*].id)
}

output "auto_stop_at" {
  value = "${local.auto_stop}Z"
}

output "load_balancer" {
  value = aws_lb.edge.dns_name
}

# What scripts/aws.sh loadtest needs to start a run.
output "loadgen" {
  value = var.loadtest ? {
    task_definition        = aws_ecs_task_definition.loadgen[0].family
    subnets                = var.task_subnet_ids
    security_group         = aws_security_group.loadtest["loadgen"].id
    dbstat_task_definition = aws_ecs_task_definition.dbstat[0].family
    dbstat_security_group  = aws_security_group.loadtest["dbstat"].id
    # Null when the store shares the ledger's server: scripts/aws.sh's dbstat has only the one
    # server to ask then, exactly as before. Set, it is where to override PGHOST to ask the store's
    # own server the same questions (state.tf's local.store_db_endpoint).
    store_db_host = var.database_per_service ? local.store_db_endpoint : null
  } : null
}
