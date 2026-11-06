# Two customer-managed keys:
#  - app: S3 documents, RDS storage + its master secret, SSM SecureStrings.
#  - pii: wraps the PII data key only (PLAN Phase 9). The backend unwraps it once at boot with
#    kms:Decrypt; nothing else may use it. The wrapped key itself is an SSM value made by hand:
#      aws kms generate-data-key --key-id alias/dhannow-<env>-pii --key-spec AES_256 \
#        --query CiphertextBlob --output text
#    → /dhannow/<env>/navix/pii/wrapped-data-key (docs/GO_LIVE.md).

data "aws_iam_policy_document" "kms_root" {
  statement {
    sid       = "AccountAdministers"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "app" {
  description             = "${local.prefix}: S3 documents, RDS, SSM SecureStrings"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms_root.json
}

resource "aws_kms_alias" "app" {
  name          = "alias/${local.prefix}-app"
  target_key_id = aws_kms_key.app.key_id
}

data "aws_iam_policy_document" "kms_pii" {
  source_policy_documents = [data.aws_iam_policy_document.kms_root.json]

  statement {
    sid       = "BackendUnwrapsTheDataKey"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.task.arn]
    }
  }
}

resource "aws_kms_key" "pii" {
  description             = "${local.prefix}: wraps the PII field-encryption data key"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms_pii.json

  lifecycle {
    # Losing this key makes every encrypted PAN, Aadhaar and account number unreadable.
    prevent_destroy = true
  }
}

resource "aws_kms_alias" "pii" {
  name          = "alias/${local.prefix}-pii"
  target_key_id = aws_kms_key.pii.key_id
}
