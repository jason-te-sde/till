output "url" {
  description = "The store, while it is running."
  value       = one(module.runtime[*].url)
}

output "running" {
  description = "Whether the hourly half exists."
  value       = var.running
}

output "database" {
  description = "Which PostgreSQL is deployed: \"rds\" or \"aurora\". scripts/aws.sh loadtest records it with a run's result."
  value       = var.database
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

output "auto_stop_at" {
  description = "When the safety net scales every service to zero, unless `scripts/aws.sh up` runs again first."
  value       = one(module.runtime[*].auto_stop_at)
}

output "load_balancer" {
  description = "The internal load balancer, while it is running: where a load test's shoppers arrive."
  value       = one(module.runtime[*].load_balancer)
}

output "loadgen" {
  description = "How to start a load test run, while running for one."
  value       = one(module.runtime[*].loadgen)
}

output "loadtest_log_group" {
  description = "Where the load generator and the stand-in provider write."
  value       = aws_cloudwatch_log_group.service["loadtest"].name
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
