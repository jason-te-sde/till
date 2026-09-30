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
  value = aws_cloudfront_distribution.till.id
}
