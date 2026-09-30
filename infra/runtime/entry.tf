# The way in: CloudFront, a VPC origin, an internal load balancer, the edge.
#
# CloudFront terminates TLS on its own certificate — there is no domain here to issue one for — and
# reaches the load balancer over the VPC origin: a network interface CloudFront places in the private
# subnets, so the load balancer has no public address and no path from the internet but this one.
# That is what lets the edge believe CloudFront-Viewer-Address from anything in the VPC's range.
#
# For a load test there is no CloudFront at all (docs/load-test.md): the load generator reaches the
# load balancer from inside the VPC, and leaving out the VPC origin and the distribution also leaves
# out half an hour of every start.

resource "aws_lb" "edge" {
  name               = "till"
  internal           = true
  load_balancer_type = "application"
  subnets            = var.alb_subnet_ids
  security_groups    = [var.security_groups.alb]

  # A header name nginx would reject is dropped here rather than passed to it.
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "edge" {
  name        = "till-edge"
  vpc_id      = var.vpc_id
  target_type = "ip"
  protocol    = "HTTP"
  port        = 8080

  # The default is five minutes of draining, per deployment, for an nginx that finishes an in-flight
  # request in milliseconds.
  deregistration_delay = 15

  health_check {
    path                = "/healthz"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "edge" {
  load_balancer_arn = aws_lb.edge.arn
  protocol          = "HTTP"
  port              = 80

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.edge.arn
  }
}

resource "aws_cloudfront_vpc_origin" "edge" {
  count = var.loadtest ? 0 : 1

  vpc_origin_endpoint_config {
    name                   = "till-edge"
    arn                    = aws_lb.edge.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

# Everything the viewer sent, cookies and query strings included, and CloudFront's own headers on
# top — CloudFront-Viewer-Address and CloudFront-Forwarded-Proto among them, which the edge needs.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewerAndCloudFrontHeaders-2022-06"
}

# The one header the edge cannot set for itself: it answers plain HTTP, and only CloudFront knows the
# viewer is on HTTPS. The edge sets every other security header.
resource "aws_cloudfront_response_headers_policy" "hsts" {
  count = var.loadtest ? 0 : 1

  name    = "till-hsts"
  comment = "Strict-Transport-Security; the edge sets the rest"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false
      preload                    = false
      override                   = true
    }
  }
}

resource "aws_cloudfront_distribution" "till" {
  count = var.loadtest ? 0 : 1

  enabled         = true
  comment         = "till"
  http_version    = "http2and3"
  price_class     = "PriceClass_100"
  is_ipv6_enabled = false # the edge reads the viewer's address as IPv4

  origin {
    origin_id   = "edge"
    domain_name = aws_lb.edge.dns_name

    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.edge[0].id
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
    }
  }

  # Nothing cached here by default: the edge already caches the catalogue for as long as the store
  # says, and everything else is per customer.
  default_cache_behavior {
    target_origin_id           = "edge"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.hsts[0].id
  }

  # The storefront's bundles, whose names change with their content: cached at CloudFront for as
  # long as the edge says, which is a year.
  ordered_cache_behavior {
    path_pattern               = "/assets/*"
    target_origin_id           = "edge"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.hsts[0].id
    compress                   = true
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
