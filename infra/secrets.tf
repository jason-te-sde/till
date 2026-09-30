# The service's secrets, generated here and kept in Parameter Store: SecureString parameters are
# encrypted with the account's AWS-managed key and cost nothing, where Secrets Manager is $0.40 a
# secret a month. ECS reads them into the containers' environment at start-up, so no task
# definition — which anybody who can describe it can read — contains one.
#
# Kept outside the hourly half, so a stop and a start keep the same tokens.

resource "random_password" "ledger_token" {
  for_each = toset(["client", "admin"])

  length  = 48
  special = false
}

# RDS refuses '/', '@', '"' and spaces in a master password; letters and digits cannot trip over any
# of them, or over a JDBC URL.
resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "ledger_client_token" {
  name        = "/till/ledger/client-token"
  description = "The ledger's client token: reserve, commit, release. The store's checkout holds it."
  type        = "SecureString"
  value       = random_password.ledger_token["client"].result
}

resource "aws_ssm_parameter" "ledger_admin_token" {
  name        = "/till/ledger/admin-token"
  description = "The ledger's admin token: stock adjustments. Used only behind the store's /api/ops."
  type        = "SecureString"
  value       = random_password.ledger_token["admin"].result
}

resource "aws_ssm_parameter" "db_password" {
  name        = "/till/db/password"
  description = "The RDS master password, which both services connect with, as in the compose stack."
  type        = "SecureString"
  value       = random_password.db.result
}
