# Who may open a connection to whom. The same picture as the comment at the top of
# docker-compose.yml, enforced: everything not in this table is refused, and nothing in it admits the
# internet.
#
# The descriptions are plain ASCII without apostrophes: EC2 refuses anything outside
# a-zA-Z0-9 and . _-:/()#,@[]+=&;{}!$* in a security group's description.
locals {
  tiers = {
    alb    = "The internal load balancer: CloudFront in, the edge out"
    edge   = "The edge: nginx, the storefront files, the proxy in front of the store"
    store  = "The store: catalogue, orders, sign-in"
    ledger = "The ledger: the only thing that decides a sale"
    kafka  = "The broker the ledger outbox publishes to"
    db     = "PostgreSQL: the ledger database and the store database"
    cache  = "Valkey: the store sessions"
  }

  admits = {
    edge_from_alb     = { to = "edge", from = "alb", port = 8080 }
    store_from_edge   = { to = "store", from = "edge", port = 8081 }
    ledger_from_store = { to = "ledger", from = "store", port = 8080 }
    kafka_from_ledger = { to = "kafka", from = "ledger", port = 9092 }
    kafka_from_store  = { to = "kafka", from = "store", port = 9092 }
    # The three brokers to each other: data replication on 9092, the controller quorum on 9093.
    # Both are "kafka to kafka" — the tier admits itself — which a security group needs an explicit
    # rule for, same as any other pair; being the same group does not imply they can reach each
    # other.
    kafka_from_kafka_data       = { to = "kafka", from = "kafka", port = 9092 }
    kafka_from_kafka_controller = { to = "kafka", from = "kafka", port = 9093 }
    db_from_ledger              = { to = "db", from = "ledger", port = 5432 }
    db_from_store               = { to = "db", from = "store", port = 5432 }
    cache_from_store            = { to = "cache", from = "store", port = 6379 }
  }

  # The containers go out for their images, their logs, their secrets and — the store — Cognito.
  outbound = ["edge", "store", "ledger", "kafka"]
}

resource "aws_security_group" "tier" {
  for_each = local.tiers

  name        = "till-${each.key}"
  description = each.value
  vpc_id      = aws_vpc.till.id

  tags = { Name = "till-${each.key}" }
}

resource "aws_vpc_security_group_ingress_rule" "admits" {
  for_each = local.admits

  security_group_id            = aws_security_group.tier[each.value.to].id
  referenced_security_group_id = aws_security_group.tier[each.value.from].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = "${each.value.from} to ${each.value.to}"
}

# CloudFront reaches the load balancer through a VPC origin: a network interface CloudFront places
# in the private subnets. The load balancer has no public address at all, so this list admits only
# CloudFront's path into this VPC — not anybody else's distribution.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.tier["alb"].id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "CloudFront, through the VPC origin"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_edge" {
  security_group_id            = aws_security_group.tier["alb"].id
  referenced_security_group_id = aws_security_group.tier["edge"].id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "alb to edge"
}

resource "aws_vpc_security_group_egress_rule" "outbound" {
  for_each = toset(local.outbound)

  security_group_id = aws_security_group.tier[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "ECR, CloudWatch, Parameter Store, Cognito; and the tiers above"
}
