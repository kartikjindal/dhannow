# DhanNow configuration inventory

Every setting a deployment needs, for the backend, the staff console, the app build and CI. Sources: `backend/navix-app/src/main/resources/application.yml`, the `@Value` defaults in the code, and the "Deployment must set" lines in `docs/PLAN.md` (Phases 5, 7, 8, 9, 10a, 11).

**Where values live.** In AWS the backend imports the SSM prefix `/dhannow/<env>/` at boot (`NAVIX_SSM_IMPORT`). A parameter name is the Spring property path: `/dhannow/prod/navix/sms/api-key` sets `navix.sms.api-key`, the same setting as env `NAVIX_SMS_API_KEY`. Terraform sets the env vars in section 1 and lists the SSM names (`terraform output ssm_parameters`), but it never stores a value. `infra/scripts/ssm_check.sh <env>` reports which required names are missing. Secrets are SecureStrings under the stack's app KMS key (`terraform output` → `app_kms_key_arn`).

Legend: **Secret** = SecureString, never in git or Terraform state. **Req** = `always` (broken without it), `prod` (prod refuses to boot, or a navix value would be used), `opt` (a default applies, or the feature is off).

## 1. Backend: set by Terraform on the ECS task

| Env var | Value | Notes |
|---|---|---|
| `NAVIX_ENV` | `staging` / `prod` | `prod` turns on `ProductionConfigGuard` and `PiiKeyConfig` |
| `NAVIX_SSM_IMPORT` | `aws-parameterstore:/dhannow/<env>/` | Unset locally, so local runs never read cloud config |
| `AWS_REGION` | `ap-south-1` | |
| `DB_URL` | `jdbc:postgresql://<rds>:5432/navix?sslmode=require` | RDS forces TLS |
| `DB_USERNAME` | `navix_admin` | The RDS master user; a least-privilege app role is a go-live item |
| `DB_PASSWORD` | Secrets Manager (RDS-managed master secret) | Injected by ECS, never in state. **Secret** |
| `NAVIX_S3_BUCKET` | `dhannow-<env>-documents-<account>` | |
| `NAVIX_S3_KMS_KEY_ID` | app key ARN | |
| `NAVIX_PII_KMS_KEY_ID` | pii key ARN | Prod refuses to boot without it plus the wrapped key |
| `NAVIX_STATIC_EGRESS_IP` | NAT Elastic IP | **The yml default `3.109.169.131` is navix's.** Digitap compares the declared IP with the caller |
| `NAVIX_EMAIL_PROVIDER` | `ses` | `log` locally |
| `NAVIX_SES_CONFIG_SET` | `dhannow-<env>` | Bounces and complaints go SNS → SQS |
| `NAVIX_SES_EVENTS_ENABLED` / `NAVIX_SES_EVENTS_QUEUE` | `true` / `dhannow-<env>-ses-events` | |
| `NAVIX_CORS_ORIGINS` | `https://<site>,https://<staff>` | |
| `NAVIX_APP_BASE_URL` / `NAVIX_SITE_BASE_URL` | `https://<site>` | Email/SMS links and legal page URLs |
| `NAVIX_REDIRECT_ALLOWLIST` | `https://<site>/app/cb/` | CC-34 |
| `NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH` | `/tmp/google-service-account.json` | Only when `google_service_account_ssm_name` is set; the JSON comes from SSM and is written at container start |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | |

## 2. Backend: SSM parameters under `/dhannow/<env>/`

### Keys and auth

| Parameter | Env equivalent | Secret | Req | Default | Notes |
|---|---|---|---|---|---|
| `navix/auth/secret` | `AUTH_SECRET` | yes | always | dev value, refused in prod | 32+ random chars. Also keys the OTP and limiter hashes. The staff console's `AUTH_SECRET` must be identical |
| `navix/mfa/encryption-key` | `NAVIX_MFA_ENCRYPTION_KEY` | yes | prod | none | `openssl rand -base64 32` |
| `navix/pii/wrapped-data-key` | `NAVIX_PII_WRAPPED_DATA_KEY` | yes | prod | none | `aws kms generate-data-key --key-id alias/dhannow-<env>-pii --key-spec AES_256 --query CiphertextBlob --output text`. Never rotate it without a re-encryption job (none exists) |
| `navix/bootstrap/admin-email` | `NAVIX_BOOTSTRAP_ADMIN_EMAIL` | no | opt | none | **First deploy only**, then delete |
| `navix/bootstrap/admin-password` | `NAVIX_BOOTSTRAP_ADMIN_PASSWORD` | yes | opt | none | **First deploy only**, then delete |
| `navix/captcha/secret` | `NAVIX_CAPTCHA_SECRET` | yes | opt | unset = no Turnstile | Staff sign-in. Set it together with the console's `NEXT_PUBLIC_TURNSTILE_SITE_KEY`, or every sign-in fails |
| `navix/captcha/hostnames` | `NAVIX_CAPTCHA_HOSTNAMES` | no | opt | unset = not checked | `staff.dhannow.com` |

### Lender of record (`ProductionConfigGuard` refuses blanks and `[placeholder]`s)

| Parameter | Env equivalent | Req |
|---|---|---|
| `navix/lender/legal-name` | `NAVIX_LENDER_LEGAL_NAME` | prod |
| `navix/lender/address` | `NAVIX_LENDER_ADDRESS` | prod |
| `navix/lender/registration` | `NAVIX_LENDER_REGISTRATION` | prod |
| `navix/lender/contact` | `NAVIX_LENDER_CONTACT` | prod |

The site samples (`docs/brand.json` `lender.*`) must carry the same values (`python site/check_all.py --release`).

### SMS (UltronSMS, India DLT)

**Every default below is a navix (DhanBoost) registration** and must be replaced after DLT re-registration.

| Parameter | Env equivalent | Secret | Req | Default (navix) |
|---|---|---|---|---|
| `navix/sms/api-key` (or `user` + `password`) | `NAVIX_SMS_API_KEY` / `_USER` / `_PASSWORD` | yes | prod | `demo` / `demo123` |
| `navix/sms/sender-id` | `NAVIX_SMS_SENDER_ID` | no | prod | `DHANBT` |
| `navix/sms/peid` | `NAVIX_SMS_PEID` | no | prod | navix entity id |
| `navix/sms/dlt-template-id` | `NAVIX_SMS_DLT_TEMPLATE_ID` | no | prod | navix OTP template |
| `navix/sms/otp-template` | `NAVIX_SMS_OTP_TEMPLATE` | no | prod | `Your OTP for {brand} login is {otp}. It is valid for {ttl} minutes. Do not share this OTP with anyone. - {brand}`. It must match the registered text word for word |
| `navix/sms/dlt-template-ids/<TYPE>` | `NAVIX_SMS_DLT_<TYPE>` | no | prod | navix `DHANBOOST_*_V1` ids |

`<TYPE>` covers 15 types: `KYC_APPROVED`, `KYC_REJECTED`, `KYC_REMINDER`, `LOAN_DISBURSED`, `REPAYMENT_VERIFIED`, `REPAYMENT_REJECTED`, `PAYMENT_DUE_SOON`, `PAYMENT_OVERDUE`, `LOAN_CLOSED`, `CREDIT_REJECTED`, `REBORROW_REVIEW_REJECTED`, `SETTLEMENT_APPROVED`, `REBORROW_REVIEW_APPROVED`, `REBORROW_PREAPPROVED` and `REFERRAL_REWARD_CREDITED`. The last two are promotional and need a promotional route.

### WhatsApp (SmartChat)

| Parameter | Env equivalent | Secret | Req | Default |
|---|---|---|---|---|
| `navix/whatsapp/token` | `NAVIX_WHATSAPP_TOKEN` | yes | opt | blank = every send fails (logged) |
| `navix/whatsapp/otp-template` | `NAVIX_WHATSAPP_OTP_TEMPLATE` | no | prod | `dhanboost_otp` (navix) |

### Verification providers

| Parameter | Env equivalent | Secret | Req | Default |
|---|---|---|---|---|
| `navix/signzy/base-url` | `SIGNZY_BASE_URL` | no | prod | preproduction host; prod `https://api.signzy.app` |
| `navix/signzy/token` | `SIGNZY_TOKEN` | yes | always | |
| `navix/signzy/client-unique-id` | `SIGNZY_CLIENT_UNIQUE_ID` | no | always | |
| `navix/signzy/prod-token` | `SIGNZY_PROD_TOKEN` | yes | always | PAN 206AB, geocode, e-sign |
| `navix/signzy/bsa-auth-token` | `SIGNZY_BSA_AUTH_TOKEN` | yes | opt | bank statement analysis |
| `navix/esign/callback-url` | `NAVIX_ESIGN_CALLBACK_URL` | no | always | `https://<api>/api/webhooks/signzy/contract`. The app refuses to start without it while provider = `signzy` |
| `navix/esign/callback-secret` | `NAVIX_ESIGN_CALLBACK_SECRET` | yes | always | random |
| `navix/esign/executer-name` | `NAVIX_ESIGN_EXECUTER_NAME` | no | prod | `NAVIX Finance Private Limited` (navix) |
| `navix/esign/logo-url`, `header-colour`, `button-colour` | `NAVIX_ESIGN_*` | no | opt | navix navy/gold; brand `#1F5BDB` |
| `navix/digitap/svc-base-url`, `api-base-url` | `DIGITAP_*_BASE_URL` | no | prod | demo hosts; prod `https://svc.digitap.ai`, `https://api.digitap.ai` |
| `navix/digitap/client-id`, `client-secret` | `DIGITAP_CLIENT_ID` / `_SECRET` | yes | always | |
| `navix/fintrix/client-id`, `client-secret` | `FINTRIX_CLIENT_ID` / `_SECRET` | yes | always | live and billable, no sandbox |

### App, email, push

| Parameter | Env equivalent | Req | Default |
|---|---|---|---|
| `navix/mobile/min-version` / `latest-version` / `maintenance` | `NAVIX_APP_MIN_VERSION` / `_LATEST_VERSION` / `_MAINTENANCE` | opt | `1.0.0` / `1.0.0` / `false` |
| `navix/email/from` | `NAVIX_EMAIL_FROM` | opt | `DhanNow <no-reply@dhannow.com>` |
| `navix/google/service-account-json` (SecureString) | mounted as `NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH` | before `push-fcm` or `play-integrity` goes ON | Prod refuses either flag ON without it |
| `navix/brand/*`, `navix/grievance/*` | `NAVIX_BRAND_*`, `NAVIX_SUPPORT_*`, `NAVIX_GRIEVANCE_*` | opt | From `docs/brand.json` through `site/build_brand.py`. Do not override them in SSM, or the site and the app will disagree |

### eNACH (flags OFF in v1)

Set these only before `digio-mandate` or `digio-debit` goes on. Then `NAVIX_DIGIO_PROVIDER=digio` refuses to start without all of them: `navix/digio/client-id`, `client-secret`, `webhook-secret`, `corporate-config-id` and `corporate-account-number` (Secret, except the config id).

### Optional tuning (defaults apply)

`NAVIX_PII_VIEW_ROLES` (`ADMIN,CREDIT_HEAD,CREDIT_EXECUTIVE,DISBURSEMENT_HEAD`), `NAVIX_APR_METHOD` (`IRR`, see the APR decision in GO_LIVE), `NAVIX_APP_ACCESS_TTL_SECONDS` (900), `NAVIX_APP_REFRESH_TTL_DAYS` (30), `AUTH_TTL_SECONDS` (86400), `NAVIX_INTEGRITY_PACKAGE` (`com.dhannow.app`), `NAVIX_VERIFICATION_CHAIN` (`signzy,fintrix,digitap`) and its timeouts, `NAVIX_LOG_LEVEL` (INFO), `NAVIX_REFERRAL_ENABLED` (true) and `_REWARD_PAISE` (20000), `NAVIX_S3_PRESIGN_TTL` (900), `NAVIX_NOTIF_*` pools.

Crons are Spring 6-field and run in IST through the app clock: `navix.lifecycle.cron`, `navix.extension.expiry-cron`, `navix.outbox.cron`, `navix.reconciliation.cron` (02:15), `navix.account-deletion.purge-cron` (02:30), `navix.account-deletion.retention-years` (5), `NAVIX_COMPLAINT_SLA_CRON`, `NAVIX_REMINDERS_CRON`, `NAVIX_EMPLOYMENT_RETRY_CRON`, `NAVIX_PROVIDER_HEALTH_CRON` and `NAVIX_DIGIO_DEBIT_CRON`.

### Must NOT be set in prod

| Setting | Why |
|---|---|
| `NAVIX_SMS_MOCK=true` | Fixed OTP `123456` signs anyone in (guard refuses) |
| `NAVIX_SMS_DEV_ECHO=true` | Returns the OTP in the response (guard refuses) |
| `NAVIX_ESIGN_PROVIDER=mock` | Signs inline with no Aadhaar e-sign (guard refuses) |
| `NAVIX_STAFF_MFA_ENFORCE=false` | Turns off staff TOTP (guard refuses) |
| `NAVIX_INTEGRITY_ALLOW_ANDROID_WITHOUT_CHECK=true` | Lets any client skip the challenge with a header (guard refuses) |
| `NAVIX_PII_DATA_KEY` | Local key only. Prod accepts only the KMS pair |
| `NAVIX_WHATSAPP_MOCK=true`, `NAVIX_EMAIL_PROVIDER=log` | Nothing reaches borrowers |
| `NAVIX_BUREAU_FIXTURE` | Every bureau pull returns a file |
| `NAVIX_DIGIO_PROVIDER=mock` with a mandate flag ON | Guard refuses |
| `NAVIX_BOOTSTRAP_ADMIN_*` after the first boot | The runner refuses to run again, but the secret should not linger |
| `AWS_PROFILE`, `AWS_ACCESS_KEY_ID`/`SECRET` | The task role supplies credentials |
| `RESEND_API_KEY` | Interim provider; prod sends through SES |

## 3. Staff console (Vercel project, per environment)

| Var | Value | Notes |
|---|---|---|
| `BACKEND_BASE_URL` | `https://api.dhannow.com` (staging: `https://api.staging.dhannow.com`) | Server-side BFF target |
| `NEXT_PUBLIC_API_BASE_URL` | `/api` | Same-origin BFF |
| `AUTH_SECRET` | same value as the backend's `navix/auth/secret` | **Secret.** Without it a production build refuses every staff session |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | Turnstile site key | Only once the backend has `navix/captcha/secret`. Build-time: needs a redeploy |
| `NEXT_PUBLIC_ROLE_SWITCHER` | **unset in prod** | "Act as role" switcher; non-prod only |
| `NEXT_PUBLIC_BRE_STAGING_PASS` | **never in prod** | Staging-only BRE shortcut |

The domain is `staff.dhannow.com` on Vercel (`web/vercel.json`, region `bom1`). Point the CNAME with `staff_cname_target`.

## 4. App build (`app/`)

| Define | Value | Notes |
|---|---|---|
| `--dart-define=API_BASE` | `https://api.dhannow.com` (default). Staging builds pass `https://api.staging.dhannow.com` | `app.yml` reads `vars.APP_API_BASE` |
| `--dart-define=DEMO` | `false` (default). **Never `true` in a release** | Runs the in-memory demo server |

The version comes from `pubspec.yaml` (`version: x.y.z+code`). The backend's `navix/mobile/min-version` gates force-update against it.

## 5. GitHub Actions

Settings live on the repository or on a GitHub **environment**. Deploy jobs run in environment `staging` or `prod`, and the AWS role trusts only those environment names. Turn on required reviewers and a branch rule (`main`, or `app-v*` tags) for `prod`.

| Name | Kind | Where | Used by |
|---|---|---|---|
| `AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`, `ECR_REPOSITORY`, `ECS_CLUSTER`, `ECS_SERVICE`, `ECS_TASK_FAMILY`, `ECS_MIGRATE_TASK_FAMILY`, `ECS_SUBNETS`, `ECS_SECURITY_GROUP`, `API_BASE_URL` | variables | env `staging`, `prod` | `deploy-backend.yml` (values: `terraform output github_environment_variables`) |
| `SITE_BUCKET`, `DOWNLOADS_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID`, `SITE_BASE_URL` | variables | env `staging`, `prod` | `deploy-site.yml` |
| `STAFF_BASE_URL` | variable | env `staging` | `security.yml` ZAP |
| `K6_BORROWER_TOKEN`, `K6_STAFF_TOKEN` | secrets | env `staging` | `load-test.yml` (short-lived staging tokens) |
| `K6_APPLICATION_ID`, `K6_LOAN_ID` | variables | env `staging` | `load-test.yml` (optional) |
| `VERCEL_TOKEN` | secret | env `staff-console-staging`, `staff-console-prod` | `web.yml` deploy |
| `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` | variables | same | `web.yml` deploy |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | secrets | repository | `app.yml` release signing (skipped when absent) |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | repository | `app.yml` Play internal track on `app-v*` tags (skipped when absent) |
| `NVD_API_KEY` | secret | repository | `security.yml` dependency-check (optional; without it the first run is very slow) |
| `FLUTTER_VERSION` | variable | repository | `app.yml` pins Flutter (pubspec needs ≥ 3.47.0) |
| `APP_API_BASE`, `APP_CORE_COVERAGE_MIN` (70), `APK_MAX_MB` (12), `PLAY_RELEASE_STATUS` (`draft`) | variables | repository | `app.yml` (optional overrides) |

The CI-only stack (`docker-compose.ci.yml`) generates `AUTH_SECRET` and the e2e staff password at random for each run. Nothing is stored.
