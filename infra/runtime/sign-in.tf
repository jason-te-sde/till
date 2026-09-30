# The store's Cognito app client. Created with each start, because the callback URL is the CloudFront
# address and that is new each time; the user pool it belongs to is not.

locals {
  base_url = "https://${aws_cloudfront_distribution.till.domain_name}"
}

resource "aws_cognito_user_pool_client" "store" {
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
  name        = "/till/store/oidc-client-secret"
  description = "The store's Cognito app client secret"
  type        = "SecureString"
  value       = aws_cognito_user_pool_client.store.client_secret
}
