output "static_egress_ip" {
  description = "NAT Elastic IP: give it to Digitap (and any provider allow-list); it is also NAVIX_STATIC_EGRESS_IP."
  value       = aws_eip.nat.public_ip
}

output "api_alb_dns_name" {
  value = aws_lb.api.dns_name
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.site.domain_name
}

output "rds_endpoint" {
  value = aws_db_instance.this.address
}

output "rds_master_secret_arn" {
  description = "Secrets Manager secret RDS keeps for the master user (injected as DB_PASSWORD)."
  value       = local.db_secret_arn
}

output "pii_kms_key_arn" {
  value = aws_kms_key.pii.arn
}

output "pii_kms_alias" {
  value = aws_kms_alias.pii.name
}

output "app_kms_key_arn" {
  description = "Use it for SSM SecureStrings: aws ssm put-parameter --type SecureString --key-id <this>."
  value       = aws_kms_key.app.arn
}

output "ses_dkim_tokens" {
  value = var.create_ses_identity ? aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens : []
}

output "ssm_parameters" {
  description = "Every parameter name the backend reads under /dhannow/<env>/ (values are set by hand)."
  value       = local.ssm_parameter_names
}

# Paste these into the GitHub environment's variables (Settings → Environments → <env>).
output "github_environment_variables" {
  value = {
    AWS_REGION                 = local.region
    AWS_DEPLOY_ROLE_ARN        = aws_iam_role.github_deploy.arn
    ECR_REPOSITORY             = aws_ecr_repository.backend.name
    ECS_CLUSTER                = aws_ecs_cluster.this.name
    ECS_SERVICE                = aws_ecs_service.backend.name
    ECS_TASK_FAMILY            = aws_ecs_task_definition.backend.family
    ECS_MIGRATE_TASK_FAMILY    = aws_ecs_task_definition.migrate.family
    ECS_SUBNETS                = join(",", aws_subnet.app[*].id)
    ECS_SECURITY_GROUP         = aws_security_group.tasks.id
    API_BASE_URL               = "https://${var.api_domain}"
    SITE_BUCKET                = aws_s3_bucket.this["site"].id
    DOWNLOADS_BUCKET           = aws_s3_bucket.this["downloads"].id
    CLOUDFRONT_DISTRIBUTION_ID = aws_cloudfront_distribution.site.id
    SITE_BASE_URL              = "https://${var.site_domain}"
  }
}

output "dns_records_to_create" {
  description = "When route53_zone_id is blank: the records to add at the DNS host."
  value = local.zone_given ? {} : {
    acm_validation = concat(
      [for o in aws_acm_certificate.api.domain_validation_options : { name = o.resource_record_name, type = o.resource_record_type, value = o.resource_record_value }],
      [for o in aws_acm_certificate.site.domain_validation_options : { name = o.resource_record_name, type = o.resource_record_type, value = o.resource_record_value }],
    )
    api   = { name = var.api_domain, type = "CNAME", value = aws_lb.api.dns_name }
    site  = [for n in concat([var.site_domain], var.site_aliases) : { name = n, type = "ALIAS/CNAME", value = aws_cloudfront_distribution.site.domain_name }]
    staff = { name = var.staff_domain, type = "CNAME", value = var.staff_cname_target == "" ? "<Vercel target>" : var.staff_cname_target }
    ses_dkim = var.create_ses_identity ? [
      for t in aws_sesv2_email_identity.domain[0].dkim_signing_attributes[0].tokens :
      { name = "${t}._domainkey.${var.ses_domain}", type = "CNAME", value = "${t}.dkim.amazonses.com" }
    ] : []
  }
}
