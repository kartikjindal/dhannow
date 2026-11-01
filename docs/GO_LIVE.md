# DhanNow go-live checklist

Prod is blocked until every **Blocker** below is ticked. This list gathers the external items from PLAN Phase 13 and every placeholder, flag and config the Phase Status paragraphs left for go-live. Settings are named as in `infra/ENV.md`. Nothing here has been applied to any AWS account.

## A. External items the business must supply

- [ ] **Blocker. Lender of record.** You need the legal name, registered address, RBI registration and the lender's support and grievance contact.
  - These go in `navix/lender/*` in SSM and in `docs/brand.json` `lender.*`.
  - Then `python site/build_brand.py` and `python site/check_all.py --release`.
  - The prod guard refuses to boot while any of these is blank or a placeholder.
- [ ] **Blocker. DLT re-registration** for the DhanNow principal entity: the PE-ID, a new sender ID (replacing navix's `DHANBT`), and every template worded "DHAN NOW".
  - **The OTP SMS wording changed in Phase 7.** The default is `Your OTP for {brand} login is {otp}. It is valid for {ttl} minutes. Do not share this OTP with anyone. - {brand}`. Register that text exactly, or set `navix/sms/otp-template` to the registered text.
  - Put the new ids in `navix/sms/peid`, `navix/sms/sender-id`, `navix/sms/dlt-template-id` and all 15 `navix/sms/dlt-template-ids/<TYPE>` entries. The yml defaults are navix's `DHANBOOST_*_V1` ids.
  - `REBORROW_PREAPPROVED` and `REFERRAL_REWARD_CREDITED` are promotional and need a promotional route.
- [ ] **Blocker. WhatsApp.** Register a DhanNow OTP template in SmartChat, replacing navix's `dhanboost_otp`, and set `navix/whatsapp/otp-template` and `navix/whatsapp/token`.
- [ ] **Blocker. Provider accounts and allow-listing for the new stack.**
  - Give Digitap (and anyone else who asks) the NAT IP from `terraform output stack` → `static_egress_ip`. The backend declares it as `NAVIX_STATIC_EGRESS_IP`, which Terraform sets; the yml default `3.109.169.131` is navix's.
  - Signzy: preprod + prod tokens. Set the e-sign callback to `https://api.dhannow.com/api/webhooks/signzy/contract` and generate a callback secret.
  - Fintrix: live only, billable.
  - Digio: only before eNACH goes on.
  - Point the `navix/signzy/base-url` and `navix/digitap/*-base-url` parameters at the prod hosts. The defaults are preprod and demo.
- [ ] **Blocker. SES.**
  - Verify the `dhannow.com` domain identity (DKIM CNAMEs from `dns_records_to_create` or Route 53).
  - Request production access (new accounts start in the sandbox).
  - Add SPF, DMARC and optionally a MAIL FROM domain.
- [ ] **Blocker. Play Console.** Create the app `com.dhannow.app` and set up the upload key and Play App Signing.
  - **The first AAB upload is manual.** After that, `app-v*` tags upload to the internal track.
  - Put the keystore in the `ANDROID_KEYSTORE_*` repository secrets and the publisher service account in `PLAY_SERVICE_ACCOUNT_JSON`.
- [ ] **Blocker. App signing SHA-256 for app links.** Put the Play app-signing key's SHA-256 in `docs/brand.json` `app.signingCertSha256`. Add the upload key's SHA-256 too while the APK is served directly from dhannow.com; `app.yml` prints it.
  - Then `python site/build_brand.py` regenerates `site/.well-known/assetlinks.json`.
  - Without this, the `/app/cb/*` callbacks do not open the app.
- [ ] **Blocker. Direct APK.** After a signed `app.yml` run, publish it with `deploy-site.yml` (`app_run_id`). Then put the URL, version, size (MB) and SHA-256 into `docs/brand.json` `app.apk`, rebuild and redeploy the site. `--release` refuses the placeholders.
- [ ] **Firebase project** (FCM).
  - Create a Google service account with FCM send and Play Integrity decode rights.
  - Store its JSON as SecureString `/dhannow/<env>/navix/google/service-account-json` and set `google_service_account_ssm_name`.
  - It is needed before `push-fcm` or `play-integrity` goes ON; prod refuses either flag without it.
  - Note: FCM push and Play Integrity are **not built in the app yet** (Phase 11 "Not done").
- [ ] **Blocker. Legal sign-off:** upfront interest, the KFS, the sanction letter and agreement text, and the **APR method**.
  - `NAVIX_APR_METHOD` is `IRR` by default. That prints 239,096.78% for ₹1,000 → ₹742 over 14 days; `SIMPLE` prints 906.53%.
  - Legal picks one; it is printed on every KFS.
- [ ] **Blocker. Legal and tax sign-off on loan extension.** This covers re-charging fee + GST + 14 days of interest on the same principal (₹258 per ₹1,000 plus the late charge), the 3-extension cap, the addendum text and the "Pending Legal and tax sign-off" sections on the site.
  - `loan-extension` is ON at launch only with this sign-off; otherwise switch the flag OFF.
- [ ] **Grievance officer confirmation.** Dharmendra Rajput, 9211331170, grievance@dhannow.com, in `docs/brand.json`.
- [ ] **Company details.** Confirm the CIN and the registered office shown on the site (`docs/brand.json`, carried over from navix), and the Play URL.

## B. Keys and one-time secrets (per environment)

- [ ] **Blocker.** `navix/auth/secret`: 32+ random characters. Use the **same value** as the staff console's `AUTH_SECRET` in Vercel.
- [ ] **Blocker.** `navix/mfa/encryption-key`: `openssl rand -base64 32`. Prod refuses to boot without a valid key or with enforcement off.
- [ ] **Blocker. PII data key.** Run `aws kms generate-data-key --key-id alias/dhannow-<env>-pii --key-spec AES_256 --query CiphertextBlob --output text` and store the result as SecureString `navix/pii/wrapped-data-key`.
  - `NAVIX_PII_KMS_KEY_ID` is set by Terraform.
  - Back up nothing in plaintext. Losing the key or the wrapped blob makes every PAN, Aadhaar and account number unreadable.
  - Rotating it needs a re-encryption job, which is not built.
- [ ] **Blocker. Bootstrap admin.** Set `navix/bootstrap/admin-email` and `admin-password` for the first deploy only.
  - Sign in, enrol MFA, invite the real staff, then **delete both parameters** and redeploy.
- [ ] **Blocker. Demo staff rows.** The V10, V42 and V55 rows with `@navix.example` addresses have no password, but they exist. Deactivate or delete them through the console or SQL before go-live.
- [ ] `navix/captcha/secret` + `hostnames` and the console's `NEXT_PUBLIC_TURNSTILE_SITE_KEY`: set all of them together, or none.
- [ ] **Decision: `navix.integrity.allow-android-without-check`.** It stays `false`; prod refuses `true`.
  - With `play-integrity` OFF, app password sign-in skips Turnstile under the per-IP and per-device caps (Phase 11), and the OTP request is capped (CC-36/42).
  - Decide whether to launch like that or to wait for the app's Play Integrity work and the service account, then turn `play-integrity` ON.

## C. Flags and config at launch (`feature_flag` rows / SSM)

- [ ] **Blocker.** `onboarding-paused` is **seeded ON** (Phase 8 found it). Switch it OFF when lending opens.
- [ ] Turn `loan-extension` ON only with the sign-off in A. `emi-conversion`, `payment-gateway`, `digio-mandate` and `digio-debit` stay **OFF**.
- [ ] `push-fcm` and `play-integrity` stay OFF until the app supports them and the Google service account is mounted.
- [ ] `penal-charges` is seeded; the frozen `penal_enabled` on each loan decides.
- [ ] `fintrix-bureau` is a kill switch (SQL). Keep `digitap-crif` OFF unless the ALB idle timeout is raised (application.yml budget note).
- [ ] e-sign branding: set `navix/esign/executer-name` (the default is NAVIX Finance Private Limited) and the colours (default navix navy/gold; brand `#1F5BDB`).
- [ ] Set `navix/mobile/min-version` and `latest-version` to the released app version.
- [ ] Prod has none of the settings in ENV.md "Must NOT be set in prod".

## D. Infrastructure (code in `infra/terraform`, nothing applied yet)

1. [ ] Create the remote-state bucket (versioned, encrypted, private) by hand. Uncomment the `backend "s3"` block in `envs/<env>/main.tf`, then run `terraform init -migrate-state`.
2. [ ] `cp terraform.tfvars.example terraform.tfvars`. Fill in `allowed_account_ids`, `github_repository`, `route53_zone_id` (or blank) and `alarm_emails`.
   - If staging and prod share an account, set `create_github_oidc_provider` and `create_ses_identity` to `false` in the second one.
3. [ ] `terraform plan` / `apply` for **staging**.
   - Without Route 53, add the ACM validation CNAMEs from `terraform output dns_records_to_create` while the apply waits.
   - The NAT Elastic IP, the PII key and the documents bucket are protected from destroy.
4. [ ] Put the SSM values (sections A–C). Then `bash infra/scripts/ssm_check.sh staging` must report 0 missing.
5. [ ] GitHub: create the environments `staging` and `prod` with the variables from `terraform output github_environment_variables`.
   - Give `prod` required reviewers and a `main`-only deployment branch rule.
   - Create `staff-console-staging` and `staff-console-prod` for Vercel.
6. [ ] Run `deploy-backend` (staging). It builds the images, runs the Flyway migrate task, rolls the service and checks health.
   - Until this first run the service's tasks fail, because the `bootstrap` image tag does not exist.
   - After a Terraform change to the task definition (env vars), run `deploy-backend` again to roll it out.
7. [ ] Run `deploy-site` (staging). Check that `/.well-known/assetlinks.json` is served as `application/json` and that `/app/cb/digilocker` and `/app/open?to=repay` resolve. The smoke step checks the first.
8. [ ] Staff console on Vercel: create a project with root `web/` and the env from ENV.md §3. Add the domain `staff.dhannow.com` and set `staff_cname_target`.
9. [ ] Confirm the alarm e-mail subscriptions. Alarms: `OUTBOX_ALERT`, `RECONCILIATION_MISMATCH`, `NAVIX_ENV=prod` boot refusals, 5xx, p95 latency, no healthy task, memory, DB CPU and storage, SES dead letters.
10. [ ] Repeat 2–9 for **prod**, sized from the staging k6 run (`db_instance_class`, `api_min_count`).
11. [ ] **Least-privilege DB role.** The backend connects as the RDS master `navix_admin`.
    - Create an application role that owns the schema, store its password in SSM as `spring/datasource/password` (and `DB_USERNAME`), and drop the `DB_PASSWORD` secret injection.
    - Or accept the master user for v1 and record that.
12. [ ] **Data-retention review (Phase 9).** No S3 lifecycle rule removes KYC uploads after an account purge (CC-45). Provider JSON (`application_verification.derived`, bureau payloads) and `blocklist_entry.value` hold unencrypted identifiers.

## E. Code gaps that block or should precede launch

- [ ] **Blocker. Security.** `npm audit --audit-level=high --omit=dev` fails today (2026-11-02): 2 critical and 7 high findings.
  - Critical: `next` 15.1.12 (fixed in ≥ 15.5.x) and `jspdf`. High: `jspdf-autotable`, `dompurify`, `zod`, `postcss`, `sharp`, `nanoid` and `source-map-js`.
  - The Next.js advisories matter because the edge middleware does the staff JWT check.
  - The `web.yml` audit job and `security.yml` stay red until this is fixed.
- [x] Closed in Phase 11c (2026-11-02):
  - The KFS + signature gate is now on every path to disbursal, including the legacy `PRE_APPROVED` re-borrow.
  - Server-side upload type and size checks.
  - Address-proof doc types.
  - The enveloped presign.
  - Legal page versions.
  - `/app/open` app links.
- [ ] **Rejected uploads stay in S3.** The 11c HEAD check refuses a file, but the object is not deleted, and the documents bucket has no rule for it. A prefix or tag is needed so a lifecycle rule can expire such objects; that is a backend + Terraform follow-up.
- [ ] **Play Integrity is OFF at launch.** That opens the app password sign-in exception (11c): no Turnstile, capped at 30/h per IP and 10/h per device. Accept this risk explicitly, or ship Play Integrity in the app first (see B).
- [ ] **App release signing.** `app/android/app/build.gradle.kts` signs release builds with the debug key. `app.yml` re-signs the outputs with the upload key from secrets.
  - Wiring a `signingConfig` that reads `key.properties` / env is cleaner; that is an app change.
- [ ] **APK size not measured yet.** `app.yml` fails a split APK of 12 MB or more.
- [ ] **Contract gate.** Commit the `openapi` artifact from the first green `backend.yml` run as `docs/api/openapi.json`. Until then the OpenAPI diff and the app contract check only warn.
- [ ] `Dockerfile.backend` copies `deploy/certs/`, which does not exist in this repo, so its build fails. CI and deploys use `Dockerfile.backend.runtime` with a CI-built jar.
- [ ] Not done yet:
  - Phase 10b "Not done": among them the Playwright ₹10,000 staff sanction → disburse → confirm path.
  - Phase 11 "Not done": FCM push, Play Integrity, B7 location, the selfie liveness provider.
  - DSA lead e-mails still describe the navix salary product, if DSAs are used.

## F. Verification before calling it live (PLAN "Verification")

1. [ ] `mvn verify -Pit` is green and the coverage gates are met (`backend.yml`).
2. [ ] Playwright on the compose stack: a ₹1,000 sanction → UTR → second-user confirm → ACTIVE, ₹742 net, due day +14 (`web.yml` e2e; that spec is still to be written).
3. [ ] Flutter `integration_test` on an emulator: first-time journey to closure, then L2 / ₹2,000. **No CI job yet.**
4. [ ] Clock-shifted IT: DUE_TODAY → OVERDUE_D1 → OVERDUE_REVIEW, 2%/day capped at 30, a dispute pauses reminders.
5. [ ] Staging extension run: offer → Head approves → OTP → ₹318 paid → Accountant verifies → due 19 Oct → 2 Nov, "Extended 1 of 3", addendum stored. Repeat to the cap.
6. [ ] KFS, sanction letter and agreement PDFs: every field present, the lender printed, no "DhanBoost".
7. [ ] Prod boots only with real lender, grievance and DLT config: try it once with a placeholder and watch it fail. The `ProdConfigRefused` alarm fires.
8. [ ] `security.yml` (ZAP baseline on staging, `zap: true`) and `load-test.yml` (k6: 100 rps, p95 < 400 ms) are green, with results attached to the release.
