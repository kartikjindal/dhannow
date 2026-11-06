variable "environment" {
  description = "staging or prod. Names every resource dhannow-<env>-* and the SSM prefix /dhannow/<env>/."
  type        = string
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "github_repository" {
  description = "owner/repo allowed to assume the deploy role through GitHub OIDC (environment-scoped)."
  type        = string
}

variable "create_github_oidc_provider" {
  description = "The GitHub OIDC provider exists once per AWS account. false = pass github_oidc_provider_arn."
  type        = bool
  default     = true
}

variable "github_oidc_provider_arn" {
  description = "Existing token.actions.githubusercontent.com provider ARN, when create_github_oidc_provider = false."
  type        = string
  default     = ""
}

# ---- network ----
variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "az_count" {
  description = "Subnets per tier. RDS needs at least two AZs."
  type        = number
  default     = 2
}

# ---- domains ----
variable "site_domain" {
  description = "Public site apex served by CloudFront (prod: dhannow.com)."
  type        = string
}

variable "site_aliases" {
  description = "Extra CloudFront names (prod: www.dhannow.com). www is redirected to the apex."
  type        = list(string)
  default     = []
}

variable "api_domain" {
  description = "Backend host on the ALB (prod: api.dhannow.com)."
  type        = string
}

variable "staff_domain" {
  description = "Staff console host (prod: staff.dhannow.com). Hosted on Vercel; only a DNS record is made here."
  type        = string
}

variable "staff_cname_target" {
  description = "Vercel CNAME target for the staff console (e.g. cname.vercel-dns.com). Blank = no record."
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "Hosted zone for the domains. Blank = DNS is managed elsewhere: add the records from the dns_records_to_create output by hand (ACM validation waits for them)."
  type        = string
  default     = ""
}

# ---- email ----
variable "ses_domain" {
  description = "Domain verified in SES for sending (dhannow.com)."
  type        = string
  default     = "dhannow.com"
}

variable "create_ses_identity" {
  description = "One SES domain identity per account and region. Set false in the second environment when both share an account."
  type        = bool
  default     = true
}

# ---- database ----
variable "postgres_engine_version" {
  description = "Major version only, so minor upgrades do not show as drift. Pinned to 16 to match Testcontainers."
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_allocated_storage_gb" {
  type    = number
  default = 50
}

variable "db_max_allocated_storage_gb" {
  type    = number
  default = 200
}

variable "db_multi_az" {
  type    = bool
  default = false
}

variable "db_backup_retention_days" {
  type    = number
  default = 7
}

variable "db_deletion_protection" {
  type    = bool
  default = true
}

# ---- backend service ----
variable "api_cpu" {
  type    = number
  default = 1024
}

variable "api_memory" {
  type    = number
  default = 2048
}

variable "api_min_count" {
  type    = number
  default = 1
}

variable "api_max_count" {
  type    = number
  default = 4
}

variable "api_bootstrap_image_tag" {
  description = "Image tag in the task definition Terraform registers. deploy-backend.yml replaces it with the commit SHA."
  type        = string
  default     = "bootstrap"
}

variable "google_service_account_ssm_name" {
  description = "Relative SSM name (under /dhannow/<env>/) of a SecureString holding the Google service-account JSON for FCM and Play Integrity. Blank = not mounted."
  type        = string
  default     = ""
}

variable "extra_environment" {
  description = "Extra plain env vars for the backend container (non-secret only; secrets go to SSM)."
  type        = map(string)
  default     = {}
}

# ---- operations ----
variable "alarm_emails" {
  description = "Addresses subscribed to the alarm topic (each must confirm the subscription)."
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  type    = number
  default = 90
}

variable "api_p95_latency_seconds" {
  description = "ALB p95 target response time alarm (PLAN Phase 12: p95 < 400 ms)."
  type        = number
  default     = 0.4
}
