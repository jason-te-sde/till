# One log group per service, kept for a week and kept across a stop, so what happened in the last
# session can still be read in the next one. Storage is $0.03 a GB-month; what costs is writing
# ($0.50 a GB), which is why the edge's access log is the one to watch under load.
resource "aws_cloudwatch_log_group" "service" {
  for_each = toset(["edge", "store", "ledger", "kafka"])

  name              = "/till/${each.key}"
  retention_in_days = 7
}
