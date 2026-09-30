# The safety net: a deadline, and at it every service scaled to zero tasks — whether or not anybody
# is left to run `scripts/aws.sh down`.
#
# Why it exists: on 30 September a load test's deployment was left running for seven and a half
# hours after its last run, because the session that would have taken it down was interrupted and
# the credentials that could have done so had expired by the time it came back. About four dollars
# of nothing. A deadline kept inside AWS needs neither.
#
# At the deadline EventBridge Scheduler sets each ECS service's desired count to zero. The tasks are
# nearly all of the hourly cost — Fargate and the public address each one holds — so what is left is
# the load balancer, the database and the cache, about $0.05 an hour, until `down`. `up` sets the
# count back and starts the clock again (scripts/aws.sh replaces time_offset.auto_stop on every up).

resource "time_offset" "auto_stop" {
  offset_hours = var.auto_stop_hours
}

locals {
  stoppable = toset(concat(["kafka", "ledger", "store", "edge"], var.loadtest ? ["idp"] : []))
  auto_stop = formatdate("YYYY-MM-DD'T'hh:mm:ss", time_offset.auto_stop.rfc3339)
}

resource "aws_scheduler_schedule" "stop" {
  for_each = local.stoppable

  name        = "till-stop-${each.key}"
  description = "Scale ${each.key} to zero at the session's deadline"

  schedule_expression          = "at(${local.auto_stop})"
  schedule_expression_timezone = "UTC"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = var.auto_stop_role_arn
    input = jsonencode({
      Cluster      = aws_ecs_cluster.till.name
      Service      = each.key
      DesiredCount = 0
    })

    retry_policy {
      maximum_retry_attempts       = 3
      maximum_event_age_in_seconds = 3600
    }
  }

  # Created with the services it stops, so it can never name one that does not exist yet.
  depends_on = [aws_ecs_service.kafka, aws_ecs_service.ledger, aws_ecs_service.store, aws_ecs_service.edge, aws_ecs_service.idp]
}
