# The store's Cognito app client. Created with each start, because the callback URL is the CloudFront
# address and that is new each time; the user pool it belongs to is not. Not created for a load test,
# whose shoppers sign in with the stand-in provider (loadtest.tf).

locals {
  base_url = var.loadtest ? null : "https://${aws_cloudfront_distribution.till[0].domain_name}"
}

resource "aws_cognito_user_pool_client" "store" {
  count = var.loadtest ? 0 : 1

  name         = "till-store"
  user_pool_id = var.user_pool.id

  # A confidential client: the store runs the code flow on the server and holds the secret there.
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "profile", "email"]
  supported_identity_providers         = ["COGNITO"]

  # What the store builds from the request, and Cognito compares character for character.
  callback_urls = ["${local.base_url}/login/oauth2/code/idp"]
  logout_urls   = ["${local.base_url}/"]

  explicit_auth_flows           = ["ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true
}

resource "aws_ssm_parameter" "oidc_client_secret" {
  count = var.loadtest ? 0 : 1

  name        = "/till/store/oidc-client-secret"
  description = "The store's Cognito app client secret"
  type        = "SecureString"
  value       = aws_cognito_user_pool_client.store[0].client_secret
}
