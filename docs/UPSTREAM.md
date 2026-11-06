# Upstream: navix_final

`backend/` and `web/` are a fork of navix_final. Use this file to cherry-pick later navix fixes and to see what DhanNow changed at the fork.

## Fork point

| Item | Value |
|---|---|
| Base | navix `main` at `67d8f675` (26 Oct 2026) |
| Merged on top | `origin/app_navix` `49067796`…`ae244485` (Monochrome reskin, UI kit, marketing pages, redact commits) |
| Merge commit (navix-side, not pushed) | `e4107eb8` |
| Copied | `backend/`, `frontend/` → `web/`, `Dockerfile.backend*`, `docker-compose*.yml` |

The backend merged cleanly, so it is identical to `main`. All 25 conflicts were in the frontend. The rule was **`main` behaviour and `app_navix` styling**:
- BRE, Aadhaar–PAN, third-party and banking tabs, their tests, staff pages, dashboard cards: `main`. `main` already had these features, cherry-picked and developed further.
- `globals.css`: the `app_navix` theme plus `main`'s loan-behaviour cards.
- `marketing-theme.css`: `app_navix`.
- `staff-shell.tsx`: `main`'s nav groups plus the kit `UserChip`.
- `staff/verifications/page.tsx`: deleted, as on `main`.

## Changed at the fork

| Change | Why |
|---|---|
| Demo-only "redact figures" removed (`number-blur.tsx`, its test, `data-redact` markup, the CSS block, `<NumberBlur/>` in the staff layout) | PLAN fork-base decision: the app.dhanboost.com blur is turned off. It is not in `main`, and its markup broke 9 staff page tests |
| Unused symbols left by the conflict picks removed (`SnapshotOverview` and its file, `humanMinutes`, `TONE_TEXT`) | Lint |
| `scripts/indexnow-submit.mjs`, its key file, and the `indexnow` / `postbuild` npm scripts removed | DhanBoost's IndexNow key; `postbuild` pinged dhanboost.com on every build |
| Staff password hashes removed from V17, V19, V42, V55 (now `null`); V19 admin is `admin@dhannow.com` | Public repo. The seeded hashes (and their passwords in comments) were navix's |
| New `BootstrapAdminRunner` (+ test) | Gives the first admin a password from `NAVIX_BOOTSTRAP_ADMIN_EMAIL` / `NAVIX_BOOTSTRAP_ADMIN_PASSWORD`, only while it has none (PLAN Phase 9 item, done in Phase 0) |
| SSM import is `${NAVIX_SSM_IMPORT:}` (cloud sets `aws-parameterstore:/dhannow/<env>/`); no AWS profile assumed; S3 bucket default `dhannow-local-documents` | PLAN Phase 0 step 3: local runs never read cloud config. The old default pointed at navix's dev bucket |
| `backend/start-aws.sh` removed | navix AWS account IDs |
| Real person data in test fixtures de-identified: an Experian report (`samplepan.json`: addresses, emails, DOB, ID numbers, employer), six PANs, nine mobile numbers, the borrower's name | Public repo. The main-classpath copy of `samplepan.json` and `CreditBriefDemoTest` (a demo runner with no assertions) were deleted |
| `AuthControllerTest` hashes a test-only password at runtime; e2e staff/admin credentials come from `E2E_*` env vars | No seeded password left to test against |
| Contact-form recipient default `support@dhannow.com`; GA id blanked in `web/.env.example` | navix contact address and DhanBoost analytics id |
| Root `pom.xml`: `testcontainers.version` 1.21.4 | Docker Engine 29 rejects the API version older Testcontainers clients send (HTTP 400, then "no valid Docker environment") |
| Root `pom.xml`: tests run with `-Duser.timezone=Asia/Kolkata` | Windows JVMs report `Asia/Calcutta`, which the `postgres:16` image rejects as a session TimeZone |
| `DashboardCalendarTest`, `performance-page.test.tsx` fixed | Date bugs in navix tests: both fail on the 1st (and, for the calendar, the last day) of a month. Worth sending upstream |

`.gitleaksignore` allows two test-only values, with the reason for each.

## Baseline (2026-11-01, after the changes above)

| Suite | Result |
|---|---|
| `backend`: `mvn verify` | Green: 1,802 unit tests across 11 modules |
| `backend`: `mvn verify -Pit` (Testcontainers, Docker) | Green: 60 integration tests (navix-app `@Tag("integration")`) |
| `web`: `tsc --noEmit`, `next lint`, `vitest run` | Green: 907 tests in 115 files |
| `gitleaks dir .` | No leaks |

Before the fixes, untouched navix `main` had 2 backend and 1 web date-dependent failures on this date.
