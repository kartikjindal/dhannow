# Buckets: documents (KYC, loan PDFs, payment proofs; KMS), site (dhannow.com static pages),
# downloads (the APK), logs (ALB access logs). All private; the site and downloads are read only
# by CloudFront through Origin Access Control.

locals {
  buckets = {
    documents = "${local.prefix}-documents-${local.account_id}"
    site      = "${local.prefix}-site-${local.account_id}"
    downloads = "${local.prefix}-downloads-${local.account_id}"
    logs      = "${local.prefix}-logs-${local.account_id}"
  }
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets
  bucket   = each.value
  # Documents are regulatory records; never let Terraform empty that bucket.
  force_destroy = false
  tags          = { Name = each.value, Purpose = each.key }
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = aws_s3_bucket.this
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = { for k, v in aws_s3_bucket.this : k => v if k != "logs" }
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.this["documents"].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.app.arn
    }
    bucket_key_enabled = true
  }
}

# CloudFront OAC reads SSE-S3 objects without a key policy; ALB logs require SSE-S3.
resource "aws_s3_bucket_server_side_encryption_configuration" "sse_s3" {
  for_each = { for k, v in aws_s3_bucket.this : k => v if k != "documents" }
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.this["documents"].id

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "old-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
  # CC-45: KYC uploads of purged accounts are kept past the purge today. The retention rule
  # (expire by tag after the regulatory period) waits for the data-retention review (GO_LIVE.md).
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.this["logs"].id
  rule {
    id     = "expire"
    status = "Enabled"
    filter {}
    expiration {
      days = 400
    }
  }
}

# The app PUTs to presigned URLs natively (no CORS needed); the staff console may fetch previews.
resource "aws_s3_bucket_cors_configuration" "documents" {
  bucket = aws_s3_bucket.this["documents"].id
  cors_rule {
    allowed_methods = ["GET", "PUT"]
    allowed_origins = ["https://${var.staff_domain}", "https://${var.site_domain}"]
    allowed_headers = ["*"]
    max_age_seconds = 600
  }
}

data "aws_iam_policy_document" "tls_only" {
  for_each = aws_s3_bucket.this
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value.arn, "${each.value.arn}/*"]
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

data "aws_iam_policy_document" "cloudfront_read" {
  for_each = toset(["site", "downloads"])

  source_policy_documents = [data.aws_iam_policy_document.tls_only[each.key].json]

  statement {
    sid       = "CloudFrontReads"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.this[each.key].arn}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.site.arn]
    }
  }
}

data "aws_elb_service_account" "this" {}

data "aws_iam_policy_document" "logs" {
  source_policy_documents = [data.aws_iam_policy_document.tls_only["logs"].json]

  statement {
    sid       = "AlbAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this["logs"].arn}/alb/AWSLogs/${local.account_id}/*"]
    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "documents" {
  bucket     = aws_s3_bucket.this["documents"].id
  policy     = data.aws_iam_policy_document.tls_only["documents"].json
  depends_on = [aws_s3_bucket_public_access_block.this]
}

resource "aws_s3_bucket_policy" "cloudfront" {
  for_each   = toset(["site", "downloads"])
  bucket     = aws_s3_bucket.this[each.key].id
  policy     = data.aws_iam_policy_document.cloudfront_read[each.key].json
  depends_on = [aws_s3_bucket_public_access_block.this]
}

resource "aws_s3_bucket_policy" "logs" {
  bucket     = aws_s3_bucket.this["logs"].id
  policy     = data.aws_iam_policy_document.logs.json
  depends_on = [aws_s3_bucket_public_access_block.this]
}
