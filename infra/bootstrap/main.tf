# Once per account, before anything else: the bucket the main stack keeps its state in, and the
# registries its images are pushed to. Both have to exist before the main stack can be initialised or
# its services started, so neither can be part of it.
#
#   scripts/aws.sh bootstrap
#
# This stack's own state stays on the machine that ran it (infra/bootstrap/terraform.tfstate, never
# committed). Losing it orphans a bucket and three registries, which cost cents a month and come
# back under management with `terraform import`.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }
}

variable "region" {
  description = "Where everything runs."
  type        = string
  default     = "us-west-2"
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "till"
      Stack     = "bootstrap"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

# --- state ---------------------------------------------------------------------------------------

# The account id is in the name because bucket names are global. It stays out of the repository all
# the same: the only file that records the name is the backend config written below, which git ignores.
resource "aws_s3_bucket" "state" {
  bucket = "till-tfstate-${data.aws_caller_identity.current.account_id}-${var.region}"

  # Taking the bootstrap down is the last step of taking everything down, and by then the state in
  # here describes nothing.
  force_destroy = true
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  # A state file overwritten by a bad apply is recoverable from the previous version.
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "old-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "state" {
  # The state holds every generated password in plain text. TLS or nothing.
  statement {
    sid       = "TlsOnly"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state.json

  depends_on = [aws_s3_bucket_public_access_block.state]
}

# The main stack's backend settings. Written rather than committed, because the bucket name contains
# the account id; scripts/aws.sh passes it to `terraform init -backend-config`. The lock is a file
# next to the state (S3 conditional writes), so no DynamoDB table.
resource "local_file" "backend" {
  filename        = "${path.module}/../backend.hcl"
  file_permission = "0644"
  content         = <<-EOT
    bucket       = "${aws_s3_bucket.state.bucket}"
    key          = "till/terraform.tfstate"
    region       = "${var.region}"
    encrypt      = true
    use_lockfile = true
  EOT
}

# --- images --------------------------------------------------------------------------------------

# One registry per Dockerfile target, and a copy of the broker the compose stack runs: pulling Kafka
# from Docker Hub at start-up would put an anonymous rate limit between a task and starting.
resource "aws_ecr_repository" "image" {
  for_each = toset(["till/runtime", "till/edge", "till/loadtest", "till/kafka"])

  name = each.key

  # Tags are commits (and the broker's version), so a tag that could be pushed twice would be a tag
  # that could stop meaning the commit it names.
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "image" {
  for_each = aws_ecr_repository.image

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the ten most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

output "registry" {
  description = "Where scripts/aws.sh pushes the images."
  value       = split("/", aws_ecr_repository.image["till/runtime"].repository_url)[0]
}
