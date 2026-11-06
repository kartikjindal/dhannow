terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source                = "hashicorp/aws"
      version               = ">= 5.70.0, < 7.0.0"
      configuration_aliases = [aws.us_east_1]
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  prefix     = "dhannow-${var.environment}"
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.name
  is_prod    = var.environment == "prod"
  azs        = slice(data.aws_availability_zones.available.names, 0, var.az_count)
  ssm_prefix = "/dhannow/${var.environment}/"
  zone_given = var.route53_zone_id != ""
}
