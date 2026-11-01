# DhanNow staging. Plan / apply from this folder (never from CI):
#   cp terraform.tfvars.example terraform.tfvars   # fill in; terraform.tfvars is gitignored
#   terraform init && terraform plan
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }

  # Remote state. Create the bucket (versioned, encrypted, public access blocked) once by hand,
  # then uncomment, fill in and run `terraform init -migrate-state`. Use a separate bucket or key
  # per environment; never commit an account id here.
  # backend "s3" {
  #   bucket       = "<state-bucket>"
  #   key          = "dhannow/staging/terraform.tfstate"
  #   region       = "ap-south-1"
  #   encrypt      = true
  #   use_lockfile = true # S3-native locking (Terraform >= 1.10); older: dynamodb_table = "<lock-table>"
  # }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = var.allowed_account_ids
  default_tags {
    tags = { Project = "dhannow", Environment = "staging", ManagedBy = "terraform" }
  }
}

# CloudFront certificates live in us-east-1.
provider "aws" {
  alias               = "us_east_1"
  region              = "us-east-1"
  allowed_account_ids = var.allowed_account_ids
  default_tags {
    tags = { Project = "dhannow", Environment = "staging", ManagedBy = "terraform" }
  }
}

module "stack" {
  source = "../../modules/stack"
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  environment                 = "staging"
  github_repository           = var.github_repository
  create_github_oidc_provider = var.create_github_oidc_provider
  github_oidc_provider_arn    = var.github_oidc_provider_arn

  vpc_cidr = "10.40.0.0/16"

  site_domain        = var.site_domain
  site_aliases       = []
  api_domain         = var.api_domain
  staff_domain       = var.staff_domain
  staff_cname_target = var.staff_cname_target
  route53_zone_id    = var.route53_zone_id

  ses_domain          = var.ses_domain
  create_ses_identity = var.create_ses_identity

  db_instance_class        = "db.t4g.medium" # the k6 target in PLAN Phase 12
  db_multi_az              = false
  db_backup_retention_days = 7
  db_deletion_protection   = false

  api_min_count = 1
  api_max_count = 2

  google_service_account_ssm_name = var.google_service_account_ssm_name
  alarm_emails                    = var.alarm_emails
  log_retention_days              = 30
}

output "stack" {
  value = {
    static_egress_ip       = module.stack.static_egress_ip
    api_alb_dns_name       = module.stack.api_alb_dns_name
    cloudfront_domain_name = module.stack.cloudfront_domain_name
    rds_endpoint           = module.stack.rds_endpoint
    pii_kms_alias          = module.stack.pii_kms_alias
    app_kms_key_arn        = module.stack.app_kms_key_arn
  }
}

output "github_environment_variables" {
  value = module.stack.github_environment_variables
}

output "ssm_parameters" {
  value = module.stack.ssm_parameters
}

output "dns_records_to_create" {
  value = module.stack.dns_records_to_create
}
