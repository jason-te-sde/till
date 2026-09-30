output "url" {
  value = local.base_url
}

output "cluster" {
  value = aws_ecs_cluster.till.name
}

output "services" {
  value = [for service in [aws_ecs_service.kafka, aws_ecs_service.ledger, aws_ecs_service.store, aws_ecs_service.edge] : service.name]
}

output "distribution_id" {
  value = one(aws_cloudfront_distribution.till[*].id)
}

output "load_balancer" {
  value = aws_lb.edge.dns_name
}

# What scripts/aws.sh loadtest needs to start a run.
output "loadgen" {
  value = var.loadtest ? {
    task_definition = aws_ecs_task_definition.loadgen[0].family
    subnets         = var.task_subnet_ids
    security_group  = aws_security_group.loadtest["loadgen"].id
  } : null
}
