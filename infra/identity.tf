# Amazon Cognito: what Keycloak stands in for locally. The user pool, its hosted sign-in pages and
# the admins group live here, outside the part billed by the hour, so the demonstration accounts
# survive a stop. The app client is created with each start instead — its callback URL is the
# CloudFront address, which is new every time.

resource "random_id" "cognito_domain" {
  byte_length = 4
}

resource "aws_cognito_user_pool" "store" {
  name                = "till"
  deletion_protection = "INACTIVE"

  # Accounts are made here or by an operator, never by whoever finds the sign-in page.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  # Nothing here sends mail: recovery is an administrator's job, and the demonstration accounts are
  # created with their passwords already set.
  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 3
  }

  username_configuration {
    case_sensitive = false
  }
}

# The classic hosted UI. The newer managed login (version 2) needs a branding style as well, and
# nothing about signing in to a demonstration is improved by one.
resource "aws_cognito_user_pool_domain" "store" {
  domain                = "till-${random_id.cognito_domain.hex}"
  user_pool_id          = aws_cognito_user_pool.store.id
  managed_login_version = 1
}

# Members arrive in the ID token as `cognito:groups`, which is the claim the store reads.
resource "aws_cognito_user_group" "admins" {
  name         = "admins"
  user_pool_id = aws_cognito_user_pool.store.id
  description  = "Operators: the store's operator console, and /api/ops behind it"
}

# The same two people as the compose stack's Keycloak realm, with passwords generated here rather
# than the realm's, which are published in this repository. `scripts/aws.sh accounts` prints them.
locals {
  demo_accounts = {
    player   = { given = "Pat", family = "Player", admin = false }
    operator = { given = "Olive", family = "Operator", admin = true }
  }
}

resource "random_password" "demo" {
  for_each = local.demo_accounts

  length           = 20
  min_lower        = 1
  min_upper        = 1
  min_numeric      = 1
  min_special      = 1
  override_special = "!#%*-_=+"
}

resource "aws_cognito_user" "demo" {
  for_each = local.demo_accounts

  user_pool_id   = aws_cognito_user_pool.store.id
  username       = each.key
  password       = random_password.demo[each.key].result
  message_action = "SUPPRESS"

  attributes = {
    email          = "${each.key}@till.test"
    email_verified = "true"
    name           = "${each.value.given} ${each.value.family}"
    given_name     = each.value.given
    family_name    = each.value.family
  }
}

resource "aws_cognito_user_in_group" "admins" {
  for_each = { for name, account in local.demo_accounts : name => account if account.admin }

  user_pool_id = aws_cognito_user_pool.store.id
  group_name   = aws_cognito_user_group.admins.name
  username     = aws_cognito_user.demo[each.key].username
}
