# Names for the services inside the VPC — ledger.till.internal and so on — the way the compose
# network gives them `till` and `store`. Cloud Map keeps the A records pointed at whichever tasks are
# running. The hosted zone behind it is billed only if it outlives twelve hours, which a session
# that ends in `scripts/aws.sh down` does not.

resource "aws_service_discovery_private_dns_namespace" "till" {
  name        = "till.internal"
  description = "till's services"
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "service" {
  for_each = toset(["ledger", "store", "kafka"])

  name          = each.key
  force_destroy = true

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.till.id
    routing_policy = "MULTIVALUE"

    # Short, so a replaced task is found in seconds; the edge re-resolves the store every ten.
    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  # ECS says which tasks are healthy, from each container's own health check.
  health_check_custom_config {}
}
