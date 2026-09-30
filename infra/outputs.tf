output "url" {
  description = "The store, while it is running."
  value       = one(module.runtime[*].url)
}

output "running" {
  description = "Whether the hourly half exists."
  value       = var.running
}

output "cluster" {
  description = "The ECS cluster, while it is running."
  value       = one(module.runtime[*].cluster)
}

output "services" {
  description = "The ECS services, while they are running."
  value       = one(module.runtime[*].services)
}

output "distribution_id" {
  description = "The CloudFront distribution, while it is running."
  value       = one(module.runtime[*].distribution_id)
}

output "edge_log_group" {
  description = "Where the edge's access log goes."
  value       = aws_cloudwatch_log_group.service["edge"].name
}

output "demo_passwords" {
  description = "The demonstration accounts' passwords. `scripts/aws.sh accounts` prints them."
  value       = { for name, password in random_password.demo : name => password.result }
  sensitive   = true
}
