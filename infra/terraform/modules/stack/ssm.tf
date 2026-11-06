# SSM Parameter Store inventory under /dhannow/<env>/. NAMES ONLY: Terraform creates no parameter
# and holds no value (values would land in state, and a placeholder value would switch features on
# with a bad secret). Operators put each value by hand (infra/ENV.md, docs/GO_LIVE.md) and check
# with infra/scripts/ssm_check.sh. The backend imports the whole prefix at boot
# (NAVIX_SSM_IMPORT=aws-parameterstore:/dhannow/<env>/); a name maps onto a Spring property, e.g.
#   /dhannow/prod/navix/sms/api-key  ->  navix.sms.api-key
#
# required: always = the service misbehaves without it; prod = ProductionConfigGuard (or the
# provider) refuses prod without it; optional = a default applies or the feature is off.

locals {
  ssm_parameters = {
    # ---- auth and keys ----
    "navix/auth/secret"              = { secure = true, required = "always", note = "AUTH_SECRET: JWT + OTP/limiter HMAC key, 32+ random chars; the staff console's AUTH_SECRET must be the same" }
    "navix/mfa/encryption-key"       = { secure = true, required = "prod", note = "base64 of 32 random bytes (openssl rand -base64 32)" }
    "navix/pii/wrapped-data-key"     = { secure = true, required = "prod", note = "CiphertextBlob of aws kms generate-data-key --key-id alias/dhannow-<env>-pii --key-spec AES_256" }
    "navix/bootstrap/admin-email"    = { secure = false, required = "optional", note = "first deploy only, then delete" }
    "navix/bootstrap/admin-password" = { secure = true, required = "optional", note = "first deploy only, then delete" }
    "navix/captcha/secret"           = { secure = true, required = "optional", note = "Cloudflare Turnstile secret for the staff console sign-in; set together with the console's NEXT_PUBLIC_TURNSTILE_SITE_KEY" }
    "navix/captcha/hostnames"        = { secure = false, required = "optional", note = "staff.dhannow.com" }

    # ---- lender of record (prod refuses placeholders) ----
    "navix/lender/legal-name"   = { secure = false, required = "prod", note = "Legal" }
    "navix/lender/address"      = { secure = false, required = "prod", note = "Legal" }
    "navix/lender/registration" = { secure = false, required = "prod", note = "RBI registration, from Legal" }
    "navix/lender/contact"      = { secure = false, required = "prod", note = "lender support and grievance contact" }

    # ---- SMS (UltronSMS, DLT) ----
    "navix/sms/api-key"         = { secure = true, required = "prod", note = "or navix/sms/user + navix/sms/password" }
    "navix/sms/user"            = { secure = false, required = "optional", note = "" }
    "navix/sms/password"        = { secure = true, required = "optional", note = "the yml default is a demo value" }
    "navix/sms/sender-id"       = { secure = false, required = "prod", note = "re-registered DhanNow sender; the default DHANBT is navix's" }
    "navix/sms/peid"            = { secure = false, required = "prod", note = "DLT entity id of the DhanNow principal entity; the default is navix's" }
    "navix/sms/dlt-template-id" = { secure = false, required = "prod", note = "OTP template id; the default is navix's" }
    "navix/sms/otp-template"    = { secure = false, required = "prod", note = "must equal the registered OTP text word for word" }
    # Per-notification DLT ids: navix/sms/dlt-template-ids/<TYPE> for KYC_APPROVED, KYC_REJECTED,
    # KYC_REMINDER, LOAN_DISBURSED, REPAYMENT_VERIFIED, REPAYMENT_REJECTED, PAYMENT_DUE_SOON,
    # PAYMENT_OVERDUE, LOAN_CLOSED, CREDIT_REJECTED, REBORROW_REVIEW_REJECTED, SETTLEMENT_APPROVED,
    # REBORROW_REVIEW_APPROVED, REBORROW_PREAPPROVED, REFERRAL_REWARD_CREDITED (defaults are navix's).

    # ---- WhatsApp (SmartChat) ----
    "navix/whatsapp/token"        = { secure = true, required = "optional", note = "blank = every send fails (logged)" }
    "navix/whatsapp/otp-template" = { secure = false, required = "prod", note = "the default dhanboost_otp is navix's" }

    # ---- verification providers ----
    "navix/signzy/base-url"         = { secure = false, required = "prod", note = "https://api.signzy.app in prod (default is preproduction)" }
    "navix/signzy/token"            = { secure = true, required = "always", note = "" }
    "navix/signzy/client-unique-id" = { secure = false, required = "always", note = "" }
    "navix/signzy/prod-token"       = { secure = true, required = "always", note = "PAN 206AB, geocode, e-sign" }
    "navix/signzy/bsa-auth-token"   = { secure = true, required = "optional", note = "bank statement analysis" }
    "navix/esign/callback-url"      = { secure = false, required = "always", note = "https://<api>/api/webhooks/signzy/contract" }
    "navix/esign/callback-secret"   = { secure = true, required = "always", note = "random; Signzy echoes it back" }
    "navix/esign/executer-name"     = { secure = false, required = "prod", note = "the yml default is NAVIX Finance Private Limited" }
    "navix/esign/logo-url"          = { secure = false, required = "optional", note = "" }
    "navix/esign/header-colour"     = { secure = false, required = "optional", note = "brand: #1F5BDB" }
    "navix/esign/button-colour"     = { secure = false, required = "optional", note = "brand: #1F5BDB" }
    "navix/digitap/svc-base-url"    = { secure = false, required = "prod", note = "https://svc.digitap.ai in prod" }
    "navix/digitap/api-base-url"    = { secure = false, required = "prod", note = "https://api.digitap.ai in prod" }
    "navix/digitap/client-id"       = { secure = true, required = "always", note = "" }
    "navix/digitap/client-secret"   = { secure = true, required = "always", note = "" }
    "navix/fintrix/client-id"       = { secure = true, required = "always", note = "" }
    "navix/fintrix/client-secret"   = { secure = true, required = "always", note = "" }

    # ---- app and email ----
    "navix/mobile/min-version"          = { secure = false, required = "optional", note = "force-update floor (CC-52)" }
    "navix/mobile/latest-version"       = { secure = false, required = "optional", note = "" }
    "navix/mobile/maintenance"          = { secure = false, required = "optional", note = "true shows the maintenance screen" }
    "navix/email/from"                  = { secure = false, required = "optional", note = "DhanNow <no-reply@dhannow.com>" }
    "navix/google/service-account-json" = { secure = true, required = "optional", note = "FCM + Play Integrity key JSON; mounted as a file when google_service_account_ssm_name is set" }

    # ---- eNACH (flags OFF in v1; needed only before digio-mandate / digio-debit go on) ----
    "navix/digio/client-id"                = { secure = true, required = "optional", note = "" }
    "navix/digio/client-secret"            = { secure = true, required = "optional", note = "" }
    "navix/digio/webhook-secret"           = { secure = true, required = "optional", note = "" }
    "navix/digio/corporate-config-id"      = { secure = false, required = "optional", note = "" }
    "navix/digio/corporate-account-number" = { secure = true, required = "optional", note = "" }
  }

  ssm_parameter_names = { for k, v in local.ssm_parameters : "${local.ssm_prefix}${k}" => v }
}
