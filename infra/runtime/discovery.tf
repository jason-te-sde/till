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
  for_each = toset(concat(["ledger", "store"], [for id in local.kafka_ids : "kafka-${id}"]))

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

  # ECS says which tasks are healthy, from each container's own health check, so a task that has
  # started but is not ready yet is not in the answer.
  health_check_custom_config {}

  # The provider does not read that block back — AWS no longer returns the one setting it had — so
  # without this every plan would replace the service, and replacing it deregisters every running
  # task: the first redeployment left Kafka with no address until it was restarted.
  lifecycle {
    ignore_changes = [health_check_custom_config]
  }
}
