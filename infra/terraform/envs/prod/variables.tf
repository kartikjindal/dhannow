variable "aws_region" {
  type    = string
  default = "ap-south-1"
}

variable "allowed_account_ids" {
  description = "Guard against applying to the wrong account. Set in terraform.tfvars, never committed."
  type        = list(string)
}

variable "github_repository" {
  description = "owner/repo"
  type        = string
}

variable "create_github_oidc_provider" {
  type    = bool
  default = true
}

variable "github_oidc_provider_arn" {
  type    = string
  default = ""
}

variable "site_domain" {
  type    = string
  default = "dhannow.com"
}

variable "api_domain" {
  type    = string
  default = "api.dhannow.com"
}

variable "staff_domain" {
  type    = string
  default = "staff.dhannow.com"
}

variable "staff_cname_target" {
  type    = string
  default = ""
}

variable "route53_zone_id" {
  type    = string
  default = ""
}

variable "ses_domain" {
  type    = string
  default = "dhannow.com"
}

variable "create_ses_identity" {
  type    = bool
  default = true
}

variable "google_service_account_ssm_name" {
  type    = string
  default = ""
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}

variable "site_aliases" {
  type    = list(string)
  default = ["www.dhannow.com"]
}

variable "db_instance_class" {
  description = "Size it from the staging k6 run (PLAN Phase 12 measures db.t4g.medium)."
  type        = string
  default     = "db.t4g.medium"
}
