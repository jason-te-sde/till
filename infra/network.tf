# Two availability zones, each with a public and a private subnet, and no NAT gateway.
#
# The private subnets hold what nothing outside the VPC may reach: the database, the cache and the
# load balancer. The containers run in the public subnets with public addresses, and that is a cost
# decision, not an exposure: a NAT gateway is $0.045 an hour before it carries a byte, more than
# everything else here but the containers combined, and the only thing the addresses are for is
# the containers reaching ECR, CloudWatch, Parameter Store and Cognito. No security group admits
# anything from the internet; the only way in is CloudFront, through the load balancer.

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

resource "aws_vpc" "till" {
  cidr_block = var.vpc_cidr

  # Service discovery answers from a private hosted zone, which needs both.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "till" }
}

resource "aws_internet_gateway" "till" {
  vpc_id = aws_vpc.till.id

  tags = { Name = "till" }
}

resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id            = aws_vpc.till.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)

  tags = { Name = "till-public-${local.azs[count.index]}" }
}

resource "aws_subnet" "private" {
  count = length(local.azs)

  vpc_id            = aws_vpc.till.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)

  tags = { Name = "till-private-${local.azs[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.till.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.till.id
  }

  tags = { Name = "till-public" }
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# The VPC's own range and nothing else.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.till.id

  tags = { Name = "till-private" }
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# Adopted to take its rules away. Nothing here uses it, and a resource that fell back on it by
# omission should be able to talk to nothing.
resource "aws_default_security_group" "till" {
  vpc_id = aws_vpc.till.id

  tags = { Name = "till-default-unused" }
}

resource "aws_db_subnet_group" "till" {
  name       = "till"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_subnet_group" "till" {
  name       = "till"
  subnet_ids = aws_subnet.private[*].id
}
