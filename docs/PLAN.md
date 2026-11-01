# DhanNow: fork navix_final + DhanNow loan engine + Flutter Android app

## ✔ Step 1 (approved 2026-11-01): reskin the wireframe to the app_navix UI

Approved together with the component catalogue `docs/wireframes/ui-kit.html` (https://claude.ai/artifact/LWXE56nRw25JLy4SVxbGQ6), which is now the reference for every app widget.

**Scope:** only the wireframe canvas (`docs/wireframes/*.dc.html`), published to https://claude.ai/artifact/9mpCW1pqqzADmTcoVXNCqJ. No backend, web or app code is touched until you approve the wireframe.

**Source of truth:** navix_final `origin/app_navix`:
- `frontend/src/styles/theme.css`;
- `globals.css` (`.surface`, `.surface-tile`, `.btn-gold/.btn-navy/.btn-outline/.btn-ghost`, `.nav-pill`, `.pill`, `.field`, motion `rise/growY/pop`);
- `components/kit/*` (Panel, PanelHeader, Tile, Figure, MetricTile, DeltaChip, GoalProgress, SegmentBars, PillNav, UnderlineTabs);
- `(borrower)/dashboard/page.tsx` (Panel loan card, "Next milestone" with icon-pill, pill quick links).

**How:**
1. **Board CSS.** One python pass over every board's `<style>` block. Each board has its own inline CSS with the same class names (`.s`, `.card`, `.btn`, `.btn2`, `.bar`, `.hd h2`, `.f input`, `.nav`, `.pill`, `.chip`). Rewritten to app_navix values:

   | Element | Value |
   |---|---|
   | Cards | `#FAFAFA`, border `#E5E7E8`, radius 22, layered shadow + white top edge. Inner tiles white, radius 16, shadow-xs |
   | Primary button | `.btn-gold`: `#FF5B37`, white, gold shadow |
   | Secondary button | Outline pill with shadow-pill |
   | Page titles and headline figures | Bebas Neue. Section heads in Inter medium |
   | Fields | White, `#D9DBDD`, ink focus + soft ring |
   | Status pills | app_navix uppercase `.pill` style with success/warning/error/info tints |
   | Bottom nav | PillNav: white pills, active = ink pill with white text |
   | Quick links | White rounded-full rows with an arrow, as in app_navix |

   There are no large black panels: you asked for white surfaces earlier, so only small active pills and chips are ink.
2. **Hand-fixes per board** where inline styles override the class CSS:
   - amount panels;
   - the Home loan card → `Panel` style, with "Active advance" label + ink status chip;
   - the timeline dots;
   - the level bar → SegmentBars;
   - icon circles → icon-pill.
3. **Restore "Next milestone"** on C1 Home (icon-pill + title + detail + link, as in app_navix), with a due-date countdown ring.
4. **Status timeline (B12) and C3** get the DhanNow milestones: Applied → Under review → Approved → KFS accepted → Signed → Disbursal sent → Disbursal confirmed → Active → Due today → Repaid & closed.
5. **New boards:**
   - D6 Loan account details;
   - D7 Devices and sessions;
   - D8 Delete account;
   - F4 Milestone motion spec: timeline step pop/grow, money on the way → confirmed tick, credit level-up fill, dashboard countdown ring, with reduced-motion notes.

   Profile copy: no change requests at all. B11 Autopay marked hidden while eNACH is off.
6. **F2 Components** rebuilt as the app_navix kit catalogue (tokens, buttons, Panel/Tile, Figure, MetricTile, DeltaChip, SegmentBars, PillNav, UnderlineTabs, pills, fields, motion).
7. **Verification:**
   - headless-Chrome render of every board, checked for overflow (heights adjusted in the board and `canvas.json`);
   - link audit (every board reachable, every board has an exit);
   - publish to the same URL, then show you screenshots of the key boards (S3, A1, B1, B12, KfsView, Selfie, C1, C3, C6, D1, D6–D8, F2, F4) for review.

Everything below is the full build plan. It starts only after you approve the wireframe.

---

## Context

navix_final (`/Users/kartik.jindal/navix_final`) is a working, deployed lending platform (DhanBoost brand) with these parts:
- **Backend:** Spring Boot 3.4.1 / Java 21, 11 Maven modules, Flyway V1–V91.
- **Web:** Next.js 15, with a borrower web, a staff console and a BFF.

DhanNow is a different product on the same rails:
- fixed 14-day tenure;
- 10% fee + 18% GST + 14 days of interest at 1%/day, all deducted upfront;
- principal-only repayment;
- credit ladder ₹1k→2k→3k→4k→5k→7k→10k;
- collections-led loan extension (14 more days for ₹258 per ₹1,000, up to 3 times), recovery EMI, new loan statuses, separate KFS / sanction letter / agreement.

Every change is specified in `/Users/kartik.jindal/dhannow/DhanNow_Loan_Engine_Difference.md`. The borrower UX is the 74-board wireframe at https://claude.ai/artifact/9mpCW1pqqzADmTcoVXNCqJ. Its local scratchpad copy is gone, so Phase 0 pulls the boards back with Artifact read.

**Outcome:** one production-ready monorepo in `/Users/kartik.jindal/dhannow`, containing:
- a forked backend with the DhanNow engine;
- the existing static website (`site/`, dhannow.com) wired to the app;
- a forked staff console (staff.dhannow.com);
- a new lightweight Flutter Android app;
- full automated tests, CI/CD and a separate AWS stack.

Rule throughout: **reuse navix code unchanged wherever DhanNow does not differ.** Only the areas in the difference doc are rewritten.

## Decisions locked with the user (no questions after execution starts)

| Topic | Decision |
|---|---|
| Repo | Monorepo `dhannow/`: `backend/`, `web/` (staff console), `site/` (static website), `app/`, `infra/`, `docs/` |
| Namespace | Keep `com.navix`, `navix-*` modules and `NAVIX_*` env. Only the user-visible brand changes to DHAN NOW |
| Web | **Static site + app + staff console** (user decision, 2026-10-27). `dhannow.com` = the existing static site (`dhannow/website` → `site/`), which hosts legal pages, app links and the APK download. Borrowers use only the Android app; there is no borrower web login in v1. The Next.js fork is reduced to the **staff console** at `staff.dhannow.com` |
| Infra | New AWS stack: own RDS, S3, KMS, SSM prefix `/dhannow/<env>/`, ECS, ALB with HTTPS. Domains `dhannow.com`, `staff.dhannow.com`, `api.dhannow.com`. App ID `com.dhannow.app` |
| Fork base | **navix `main`** (`67d8f675`, 26 Oct). It has 141 commits that `app_navix` lacks, including the settlement-closing fix, the NACH stack, the SQL-paged worklist and per-loan grace. The 11 `app_navix` commits (Monochrome reskin + UI kit, BRE tab, Aadhaar–PAN linkage) are merged on top in Phase 0. The demo-only `redact-figures` blur is turned off |
| Design system | **app_navix UI kit**, not just its colours. See "Design system" below. Applies to the wireframe, the Flutter app and the web. **Accent changed from ember orange to cobalt blue** (user choice, 2026-10-27): primary/fills `#1F5BDB` (white text 5.9:1), links/pressed `#1A4BB8`, tint `#EEF3FF`. Wherever the sections below say ember, read cobalt |
| Payments | Manual UPI/bank + UTR + screenshot, verified by the accountant. **eNACH (Digio) code kept and updated, but `digio-mandate` and `digio-debit` stay OFF**, so the Autopay step is skipped by the journey. `PaymentGatewayPort` + webhook idempotency scaffold behind flag `payment-gateway` |
| Profile | View-only. **No mobile, email or any other detail change**, in the app or via support |
| Loan extension | **Collections-led only** (no borrower button). Officer offers → Collection Head approves → borrower confirms with OTP in the app → pays in the app or the officer logs it → Accountant verifies → due = old due + 14. Charge = fee + GST + 14 days interest + late charge to date. Cap 3 (config) + ADMIN override. Borrower sees "Extended n of 3", new due date and history. Flag `loan-extension` ON |
| Recovery EMI | Built, flag OFF |
| Penal | 2%/day on principal, capped at 30 days. Moved into versioned config and disclosed in the KFS |
| Lender of record | Config values. **Prod boot fails if they are still placeholders** |
| App auth | OTP + password. Play Integrity replaces Turnstile for app clients. Refresh tokens and logout added |
| Push | FCM channel + device tokens |
| Selfie | App captures straight/right/left frames → staff manual verification queue. `LivenessPort` lets ML Kit or an API be added later |
| Eligibility | navix rules unchanged (salaried + self-employed, one live loan, reject cool-offs 90/90/365/30). Ladder: any day late = no upgrade |
| Cooling-off | None in v1 (KFS: "not applicable") |
| Due time | 23:59:59 IST on day 14, no holiday shift |
| Disbursal | Manual transfer. Disbursement Head enters the UTR, then an Accountant confirms. The loan turns ACTIVE and day 0 starts at confirmation |
| Hardening | Maker-checker enforced; PII encrypted + masked; no seeded passwords; OTP and limiter state moved to Postgres (no Redis) |
| App | English only, i18n-ready (ARB files), wireframe theme, minSdk 24 |

---

## Design system: the app_navix UI kit

Source: `navix_final` branch `origin/app_navix`, commit `49067796`.
- `frontend/src/styles/theme.css`: single source of truth.
- `frontend/src/app/globals.css`: `.btn`, `.card`, `.field`, `.pill`, motion.
- `frontend/src/components/kit/*`:
  - `surface`: Panel;
  - `figures`: Figure, MetricTile, DeltaChip;
  - `viz`: GoalProgress, SegmentBars, DotMatrix, CapsuleBarChart;
  - `nav`: PillNav, UnderlineTabs;
  - `menu`: Dropdown, PillSelect;
  - `donut`;
  - `chart`.
- Borrower shell + dashboard (`(borrower)/layout.tsx`, `dashboard/page.tsx`).
- Login segmented toggle.

What the wireframe got wrong (only colours were taken) and is now carried over exactly:

| Element | app_navix value |
|---|---|
| Surfaces | Backdrop `#E4E6E7` with the faint grid. App frame/page `#F2F3F3` in a rounded frame (`--r-frame` 32). **Cards `#FAFAFA`** with border `#E5E7E8`, radius **22**, `--shadow-sm` + white top edge (`inset 0 1px 0 #fff`). Inner tiles, inputs and pills `#FFFFFF` |
| Type | **Bebas Neue for page titles (h1) and headline figures**. Inter for everything else, tabular numbers for money. Mixed-case section headings in Inter semibold |
| Buttons | Pill radius. Primary = `.btn-gold` (ember `#FF5B37`, white text, `--shadow-gold`, lift on press, arrow nudges). Dark = `.btn-navy` (`#0B0B0C`). Secondary = `.btn-outline` (white, `--shadow-pill`). Ghost. Focus ring `0 0 0 4px rgb(255 91 55 / .18)` |
| Text on light | Accent text and links `#C73A18` (`--gold-dark`, ≥ 4.5:1) |
| Fields | White, border `#D9DBDD`. Focus: ink border + soft ring. Label goes ink on focus. Inline `.err` in `#C62A2F` |
| Status | Success `#17804F` / `#E3F7EC`. Warning `#8A6200` / `#FEF6D8`. Error `#C62A2F` / `#FDECEC`. Info violet `#6A4DE0` / `#F1EDFE`. Pills uppercase 10px, as in `.pill` |
| Data viz | Ember, sun `#F8CF40`, mint `#5CD398`, violet `#8E6CF2`, ink, sky. Empty track `#D6D9DC` |
| Navigation | PillNav and UnderlineTabs. Bottom nav = pill indicator |
| Motion | `rise` (fade + 10px up + 0.985 scale), `growY`, `pop`; `--ease-out` cubic-bezier(.16,1,.3,1); all off under reduced motion |

**Accessibility exception, noted:** white on `#FF5B37` is about 3:1. It is kept for primary buttons only because app_navix uses it, with 16px semibold labels. Every other ember text uses `#C73A18`.

**Flutter mapping:**
- `DnTokens` (static constants in `app/lib/core/theme/tokens.dart`) carries every token above. Not a `ThemeExtension`: the app is light-only in v1 (CC-58), so there is nothing to interpolate.
- Widgets mirror the kit:
  - `DnPanel` (card + shadow + top edge);
  - `DnFigure` (Bebas amount + Inter label);
  - `DnMetricTile`;
  - `DnDeltaChip`;
  - `DnGoalProgress` (credit ladder);
  - `DnSegmentBars` (level bar);
  - `DnPillNav` / `DnUnderlineTabs`;
  - `DnPill`;
  - `DnButton.gold/.navy/.outline/.ghost`;
  - `DnField`.
- Motion via `AnimatedSwitcher` / `TweenAnimationBuilder` with the same curves. `MediaQuery.disableAnimations` respected.
- Fonts bundled: Inter variable + Bebas Neue, subset to Latin + ₹.

## Phase 0a: Wireframe rework (before any app code; week 1)

The wireframe is the app's spec, so it is fixed first and re-published to https://claude.ai/artifact/9mpCW1pqqzADmTcoVXNCqJ.

1. **Reskin all boards to the app_navix kit** (table above):
   - cards to `#FAFAFA` + shadow + radius 22;
   - Bebas page titles;
   - `.btn-gold` primary buttons;
   - pills;
   - PillNav bottom bar.

   Board F2 (Components) is rebuilt as the kit catalogue.
2. **Milestones + animation:**
   - Restore the **Next milestone card on the active dashboard (C1)**, with a countdown ring to `due_at` (days left; turns red on DUE_TODAY).
   - Status timeline (B12 Status, C3 in-progress dashboard) gains the DhanNow states: Applied → Under review → Approved → KFS accepted → Signed → Disbursal sent → **Disbursal confirmed** → Active → Due today → Repaid & closed. Overdue, Restructured and On hold are shown as side branches.
   - New board **F4 "Milestone motion spec"**, with a frame-strip per animation:
     - (a) timeline step reached: dot `pop`, connector `growY` 400 ms;
     - (b) "Money on the way": pulsing transfer arc while DISBURSAL_CONFIRMATION_PENDING, then a tick `pop`;
     - (c) credit level-up on closure: SegmentBars fill to the new level (600 ms) + one-time confetti-free "Level 2 unlocked" `rise`;
     - (d) dashboard milestone ring countdown.

     Each has a reduced-motion fallback.
3. **Account boards:**
   - D1 Profile stays view-only with no edit or change-request links. Remove "To change anything, contact support".
   - New **D6 Loan account details**: loan account no., lender of record, credit level + history, auto-repay state (shows "Off" while eNACH is disabled), documents (KFS / sanction / agreement / schedule / receipts).
   - New **D7 Devices and sessions**: this device, other devices with last seen, "Log out of all devices".
   - New **D8 Delete account**: rules, blocked-while-loan-open state, confirmation with OTP, then request-received state.
   - Settings (D2) links to D7 and D8.
4. **eNACH off:** B11 Autopay is marked "hidden while eNACH is disabled (flag)". The Home auto-repay row reads "Pay manually by 19 Oct".
5. Re-run the link audit: every board reachable, every board has an exit. Re-render all boards in headless Chrome to check overflow.

---

## Phase 0: Fork and bootstrap (week 1)

1. In `dhannow/`: export navix `main` (`git archive 67d8f675`), then copy `backend/`, `frontend/` → `web/`, `Dockerfile.backend*` and `docker-compose*.yml`. Merge the 11 `origin/app_navix` commits (`49067796`…`ae244485`) on a branch, resolve conflicts in favour of `main` behaviour and `app_navix` styling, and run both test suites before continuing.
   - Exclude `target/`, `node_modules/`, `.env`, `.code-review-graph/` and `docs/claude-in-chrome/` (it contains credentials).
   - **The repo is public (user decision, 2026-10-30), so scrub before the first push:**
     - staff password hashes seeded in V17, V19, V42 and V55: blank them and use `BootstrapAdminRunner` (Phase 9, done here instead);
     - remove `aws.md`, `backend/start-aws.sh` and `docs/github-actions-aws-backend-deploy.md` (navix AWS account IDs);
     - replace the committed default `navix.auth.secret` in `ProductionConfigGuard` with a non-usable dev value; the guard keeps refusing it in prod;
     - test fixtures with bcrypt hashes are kept only if they are test-only values.

     Run `gitleaks detect` (plus a grep for 12-digit AWS IDs and bcrypt `$2a$` strings) over the tree; the push is blocked until it is clean. CI runs the same scan on every PR.
   - Record the navix commit hash (`67d8f675`) in `docs/UPSTREAM.md` so later navix fixes can be cherry-picked.
2. Pull the wireframe boards into `docs/wireframes/` (Artifact read of every `project/*.dc.html` + `canvas.json`). Move the difference doc and the DhanNow `.docx` sources into `docs/product/`.
3. Delete the `backend/.env` trap:
   - `AWS_PROFILE` is never defaulted.
   - The SSM import uses `/dhannow/${NAVIX_ENV}/`.
   - The local profile disables the SSM import entirely.
4. Baseline green: `./mvnw verify -Pit` and `web` `npm run typecheck && npm run lint && npm test` pass **before any change** (snapshot the test counts).
5. Add JaCoCo (fails under 80% line coverage on `navix-loan` and `navix-collections` engine packages) and ArchUnit (module dependency rules). Both are new; nothing else is added to tooling. **Moved to the first step of Phase 1:** the gated engine packages (`LoanMath`, `PricingService`, then `CreditLevelService`) are created there, so a Phase 0 gate would have nothing to measure.

**Acceptance:** fork builds and all existing navix tests pass unchanged.

**Status (2026-11-01): done.** Fork point, merge resolution, scrub list and baseline counts are in `docs/UPSTREAM.md`. Backend `mvn verify` (1,802 unit) and `mvn verify -Pit` (60 integration) are green; web typecheck, lint and 907 vitest tests are green; `gitleaks` is clean, and `.github/workflows/secrets.yml` runs it on every push and PR. Three navix tests were date-dependent and failed on the 1st of the month; they are fixed and listed in UPSTREAM.md.

## Phase 1: Versioned config + DhanNow money math (weeks 1–2)

**Migrations** (start at V92; every table has `BaseAuditEntity` columns):
- **V92 `pricing_config`:**
  - `id`, `version int unique`, `pf_rate numeric(6,4)`, `gst_rate`, `daily_rate`, `tenure_days int`, `interest_mode` (`UPFRONT` | `ACCRUED`), `penal_enabled bool`, `penal_rate`, `penal_cap_days`, `prepay_rebate bool`, `rounding` (`HALF_UP_PAISA`), `effective_from timestamptz`, `approved_by`, `approved_at`, `status` (`DRAFT` | `ACTIVE` | `RETIRED`).
  - Seed v1 = 0.10 / 0.18 / 0.01 / 14 / UPFRONT / penal true 0.02 cap 30 / rebate false.
- **V93 `product_config`:** `version`, `allowed_amounts_paise bigint[]` (1000,2000,3000,4000,5000,7000,10000 in rupees ×100), `min_paise`, `max_paise`, `due_cutoff_time time` (23:59:59), `timezone` ('Asia/Kolkata'), `one_live_loan bool`, `late_blocks_upgrade_days int` (1), `status`.
- **V94:**
  - Add `pricing_version_id` and `product_version_id` to `loan_application` and `loan`.
  - Add `upfront_interest_paise`, `tenure_days` and `due_at timestamptz` to `loan`.

**Code:**
- **`LoanMath`:** becomes an instance class `LoanMath(PricingSnapshot)`. Static constants are kept only as the seed defaults.
  - New `upfrontInterestPaise(p) = p × daily × tenure`.
  - `netDisbursed = p − pf − gst − upfrontInterest` when the mode is UPFRONT.
  - `totalRepayable = p` (UPFRONT).
  - `dueDate = disbursedOn + tenureDays`; `dueAt = dueDate 23:59:59 IST`.
  - `dueDateFromSalary` is no longer called on the DhanNow path; it stays for ACCRUED mode.
- **`PricingService`:** loads the ACTIVE version. A **snapshot is frozen on the application at offer time** and copied to the loan at disbursal. All money reads use the snapshot, never "current".
- **Fix navix inconsistencies:**
  - `LoanService.disburse` uses `Clock` (IST) and the snapshot tenure.
  - `OfferService.tenureDays` uses the same source.
  - KFS figures and ledger figures can no longer differ.
  - A single `Clock` bean is introduced app-wide.
- **`LimitCalculator`:** returns the **credit-level amount** (Phase 2) for DhanNow. The salary-based method stays but is unused.
- **Stage-gated feature flags:** `loan-extension` (ON), `emi-conversion`, `penal-charges` (ON), `prepay-rebate`, `payment-gateway`, `cooling-off-exit` (OFF, unused), `play-integrity`, `push-fcm`. Seeded in V95.

**Tests:**
- `LoanMathDhanNowTest` uses the 7-row worked table (net 742 / 1,484 / 2,226 / 2,968 / 3,710 / 5,194 / 7,420; repay = principal).
- Property tests with jqwik (test scope only): `pf + gst + interest + net == principal` for every allowed amount; all values are integer paise.
- Snapshot-immutability test: changing the active pricing does not alter an existing offer or loan.

**Status (2026-11-01): done.** `mvn verify` is green (1,848 unit) and `mvn verify -Pit` is green (63 integration, up from 60; V92–V96 applied to Testcontainers Postgres). Gates: JaCoCo report on every module plus an 80% line check on the engine classes in `navix-loan` (`LoanMath`, `PricingService`, `CreditLevelService`, the snapshot records); ArchUnit (`ArchitectureRulesTest`, test scope) with three rules: rate constants read only by `LoanMath`, `LoanMath` used only in `com.navix.loan`, no package cycles between modules. Migrations V92–V95 as listed. Tests: `LoanMathDhanNowTest`, `LoanMathDhanNowPropertiesTest` (jqwik), `PricingServiceTest` (CC-03), CC-01 in `LoanServiceTest` and the flow IT. Deviations:
- Rows without a frozen version (pre-V92) keep the navix terms: `new LoanMath()` is `LoanMath.NAVIX_PRICING`.
- A DB trigger freezes a version's terms once it leaves DRAFT; a partial unique index allows one ACTIVE version.
- `play-integrity` and `push-fcm` are seeded OFF until Phase 8 builds them.
- `LimitCalculator` is unchanged: in navix it only feeds reporting. The sanctionable limit is set from the credit level (Phase 2).
- `prepay_rebate` and `late_blocks_upgrade_days` are stored but not read (no rebate in v1; on-time is `closed ≤ due_at`). The `penal-charges` flag is seeded; the frozen `penal_enabled` decides the charge.
- The offer and KFS show an indicative due date (expected disbursal + 14, CC-01); the final date is set at disbursal.

## Phase 2: Credit ladder (week 2)

- **V96 `credit_level`:** `customer_id PK`, `level smallint` (1–7), `max_paise`, `updated_at`.
- **V96 `credit_level_history`:** `customer_id`, `from_level`, `to_level`, `reason` (`FIRST_LOAN` | `ON_TIME_REPAID` | `LATE_REPAID_NO_CHANGE` | `MANUAL_EXCEPTION`), `loan_id`, `approved_by`, `reason_code`, `at`.
- **`CreditLevelService`:**
  - First loan = L1.
  - On loan CLOSED, `closed_at ≤ due_at` **and** no partial-only closure → +1 level (max 7). Otherwise unchanged.
  - Manual exception: ADMIN only, reason code required, audited.
- **Offer amount:** `OfferService.chooseAmount` accepts only `allowed_amounts ≤ level max`. `customer_limit_override` (V69) is reused as the manual-exception input and mapped onto a level.
- **Reloan:** `ApplicationFlowService.reborrow` uses the level, not 25% of salary. The navix delinquency rejection, the one-live-loan rule and the cool-offs are kept.
- **API:** `GET /api/me/credit-level` → `{level, maxPaise, nextLevelPaise, ladder[]}`.
- **Tests:**
  - on-time → up;
  - 1 minute late → same;
  - partial then full on time → up;
  - L7 stays at L7;
  - manual exception needs ADMIN plus a reason.

**Status (2026-11-01): done** (see Phase 1). V96 (`credit_level`, `credit_level_history`), `CreditLevelService`, `GET /api/me/credit-level`, `POST /api/customers/{id}/credit-level` (manual exception). Tests: `CreditLevelServiceTest`, `RepaymentServiceTest.partialThenFullOnTimeRepaymentMovesUpOneLevel`, flow IT. Deviations:
- The sanction itself is capped (a ladder amount ≤ the level max, `requireSanctionable`); `chooseAmount` and `acceptOffer` then accept only the frozen ladder amounts ≤ the sanctioned ceiling (CC-04).
- `customer_limit_override` (V69) is not read on the DhanNow path. The manual exception is `CreditLevelService.manualException` (ADMIN + reason code, history row with the approver).
- `paid_on` is a date, so on-time compares the start of the `paid_on` IST day with `due_at`.
- "Extended loans don't raise the level" waits for the extension itself (Phase 6).

## Phase 3: Loan lifecycle and status machine (weeks 3–4)

Status is split into separate dimensions on `loan`, as the deck requires.

| Column | Values |
|---|---|
| `lifecycle_status` | `DISBURSEMENT_PENDING`, `ACTIVE`, `DUE_TODAY`, `OVERDUE_D1`, `OVERDUE_REVIEW`, `RESTRUCTURE_PENDING`, `RESTRUCTURED`, `CLOSED` |
| `hold_status` | `NONE`, `DISPUTED`, `FRAUD_REVIEW` |
| `repayment_status` | `UNPAID`, `PARTIAL`, `PAID_PENDING_VERIFICATION`, `PAID` |

`credit_level` lives on the customer.

**V97:**
- Drop and recreate `loan_status_check` to cover the old `status` column plus the new columns.
- Backfill the old `status` → `lifecycle_status`.
- Keep the `status` column, written in sync for navix reports. It is removed in a later release.
- Widen the `loan_document.type` check.

**Transitions** (`LoanLifecycle` enum with an allowed-transition map, like `ApplicationStatus`):
- `DISBURSEMENT_PENDING → ACTIVE` on accountant confirmation of disbursal (Phase 4).
- `ACTIVE → DUE_TODAY` at 00:00 IST on the due date.
- `DUE_TODAY → OVERDUE_D1` at `due_at` + 1 s.
- `OVERDUE_D1 → OVERDUE_REVIEW` when DPD ≥ `overdue_review_dpd` (config, default 7, the same as navix's `OVERDUE_BUCKET_DAYS`). This also creates or keeps a collection case (navix `markInCollections` is reused).
- Any open state → `CLOSED` when outstanding = 0 after verified reconciliation.
- `OVERDUE_*` → `RESTRUCTURE_PENDING` → `RESTRUCTURED` (Phase 6).
- `hold_status=DISPUTED` pauses reminders, collections calls and NACH presentment.
- `hold_status=FRAUD_REVIEW` blocks new applications for the customer.

**`LoanLifecycleScheduler`:**
- Runs every 5 min. It is idempotent, guarded by a ShedLock-style Postgres advisory lock (new small `scheduler_lock` table, V98), and all times are IST via `Clock`.
- `PaymentReminderScheduler` and `MandateDebitScheduler` are reused but now read `lifecycle_status`/`hold_status`.
- `Loan.effectiveStatus` is replaced by the persisted state.

**Ladder hook:** on CLOSED, call `CreditLevelService`.

**Tests:**
- Testcontainers IT with a fixed `Clock` stepping through day 13 → 14 00:00 → 23:59:59 → 00:00:01 (D1) → D7 (REVIEW).
- Dispute pause.
- Fraud block.
- Two schedulers running at once → single transition.

**Status (2026-11-01): done.** `mvn verify` is green (1,863 unit) and `mvn verify -Pit` is green (69 integration, up from 63; V97–V98 applied to Testcontainers Postgres). Migrations:
- **V97:** `lifecycle_status`, `hold_status` and `repayment_status` on `loan`, backfilled from `status` and the payments on file; one recreated `loan_status_check` covers all four columns. Also widens `loan_document.type` to the Phase 5–6 types and adds `product_config.overdue_review_dpd` (default 7, frozen with the other terms by the existing trigger).
- **V98:** `scheduler_lock`.

Code:
- `LoanLifecycle` (allowed-transition map, `forTime` clock rule, and the navix `status` it writes alongside), plus `LoanHold` and `RepaymentStatus`.
- `Loan.moveTo` is the only lifecycle writer; an illegal move is 422 `ILLEGAL_TRANSITION`.
- `LoanLifecycleService` + `LoanLifecycleScheduler` (every 5 min, IST `Clock`) move a loan with a row lock.
- `SchedulerLock` is a ShedLock-style lease. It also wraps `PaymentReminderScheduler` and `MandateDebitScheduler`, which fixes the single-instance issue.
- The reminder sweep reads `lifecycle_status` and skips DISPUTED loans. The NACH sweep skips loans with `Loan.presentmentPaused`.
- DISPUTED loans are left off the collections worklist (`listCollectible` and the worklist SQL). FRAUD_REVIEW blocks `createDraft`/`reborrow` (`FRAUD_REVIEW`).
- `RepaymentService` closes through the lifecycle (the ladder hook still runs once, on the first close) and keeps `repayment_status` current.
- `LoanView` (`/api/loan/{id}`, customer 360) and `LoanRegisterRow` gain `lifecycleStatus`, `holdStatus` and `repaymentStatus`. These fields are additive.

Tests:
- `LoanLifecycleTest` (map, clock boundaries, CC-13).
- `LoanLifecycleServiceTest` (CC-13, review day from config).
- `RepaymentServiceTest.cc09_partPaymentLeavesTheLoanOpenAndPartial`.
- `MandateDebitSchedulerTest.cc11_presentmentSkippedWhilePendingVerificationDisputedOrRestructurePending`.
- Dispute and fraud unit tests.
- `LoanLifecycleIntegrationTest`: day 13 → 14 00:00 → 23:59:59 → +1 s (D1) → D7 (REVIEW); `cc13_…`; `cc14_twoSchedulersAtOnceMakeASingleTransition`; dispute pause; fraud block; V97 checks.

JaCoCo's 80% gate now also covers `LoanLifecycle` and `LoanLifecycleService`.

Deviations:
- **Collection case.** OVERDUE_REVIEW is reached through navix `markInCollections`, which sets IN_COLLECTIONS but does not create a `collection_case` row; an officer opens the case, as in navix. An officer opening a case on a past-due loan also moves it to OVERDUE_REVIEW, so `status` and `lifecycle_status` never disagree.
- **Reminder sweep and overdue reminders.** The reminder sweep no longer calls `markInCollections` (that flipped loans to IN_COLLECTIONS at D1). The lifecycle job owns it at the review day. Overdue reminders now run D1–D7 as navix documented, rather than stopping at D1.
- **`Loan.effectiveStatus`.** It is kept for the navix `status` fields shown to staff and the borrower, and for its SQL twins. The schedulers and the new fields read the persisted state.
- **CC-14 locking.** CC-14 uses the job lease plus a `SELECT … FOR UPDATE` per loan, instead of `@Version` on `loan`. `@Version` (409 `STALE_STATE`) lands with CC-21.
- **CC-11.** Only the skip half is built; the "both clear → refund case" half is Phase 6.
- **Who sets holds.** Nothing sets a hold yet: the dispute endpoint is Phase 7. The hold does not stop the lifecycle clock.
- **Loan start state.** The loan row is still minted ACTIVE at disbursal. `DISBURSEMENT_PENDING → ACTIVE` arrives with the accountant confirmation (Phase 4).

## Phase 4: Disbursal confirmation + maker-checker (week 4)

- **New `ApplicationStatus.DISBURSAL_CONFIRMATION_PENDING`** between the Disbursement Head's UTR entry and ACTIVE (V99 updates the application status check).
  - `disbursementDecision` stores the UTR.
  - New `POST /api/staff/applications/{id}/disbursal/confirm` (ACCOUNTANT) runs `finalizeDisbursal` with `disbursedOn` = the confirmed date.
- **Enforce SoD** by wiring the existing unused `SeparationOfDutiesGuard`:
  - sanctioner ≠ disburser ≠ confirmer;
  - payment recorder ≠ verifier;
  - EMI requester ≠ approver;
  - extension proposer ≠ approver (Collection Head) ≠ payment verifier (Accountant).
  - ADMIN no longer bypasses SoD (it still bypasses role checks).
- **Tests:** an IT for every pair, rejected with 422 `SOD_VIOLATION`.

## Phase 5: Documents (weeks 5–6)

- **Renderers:** split `SanctionLetterPdfRenderer` into:
  - `KfsPdfRenderer` (RBI KFS fields, APR, schedule, penal, cooling-off "not applicable", grievance, lender);
  - `SanctionLetterPdfRenderer` (new DhanNow template fields: letter no., application no., loan account no., validity, acceptance deadline, lender, purpose, APR, deductions, payable, first and final due, beneficiary, conditions, acceptance timestamp);
  - `LoanAgreementPdfRenderer`.
  - Remove the "no auto-debit mandate" line.
  - All three are fed by one `DocumentFacts` built from the frozen snapshot.
- **`AprCalculator`:** IRR on cash flows (−net at t0, +principal at t+14), annualised as (1+r)^(365/14)−1. The method sits behind an interface so Compliance can switch to simple annualisation.
  - Unit test: ₹1,000 → 742 / 1,000 / 14 days, with the expected APR pinned.
- **Lender/brand/grievance:** `@ConfigurationProperties` for `navix.lender.*`, `navix.brand.*` and `navix.grievance.*`.
  - Grievance: Dharmendra Rajput / 9211331170 / grievance@dhannow.com.
  - `ProdConfigGuard` fails startup in the `prod` profile if any lender value is blank or still a `[placeholder]`.
- **Storage:** use the existing `loan_document` table (type `KFS` | `SANCTION` | `AGREEMENT` | `SCHEDULE` | `CLOSURE_RECEIPT` | `EXTENSION_ADDENDUM` | `EMI_ADDENDUM`, plus `version`, `pricing_version_id`, `s3_key`, `sha256`, `accepted_at`, `accepted_ip`, `acceptance_evidence jsonb`) via V100.
  - `application_document` stays for KYC uploads.
- **Flow:** the KFS is generated and must be **acknowledged** (`POST /offer/kfs/ack`, a new journey step `KFS` after SUMMARY, matching wireframe board KfsView) before the e-sign init is allowed. The Signzy e-sign flow is reused unchanged, and the signed copy is stored as `AGREEMENT`.
- **Access policy:**
  - `GET /api/loans/{id}/documents` → list.
  - `GET /api/loans/{id}/documents/{docId}/url` → S3 presigned GET, TTL 5 min.
  - Owner check plus an optional step-up (`X-Step-Up` OTP token).
  - Staff access is audited.
- **Tests:**
  - Each PDF has a golden text extraction (PDFBox, test scope) asserting every required field.
  - No "DhanBoost" string in any output.
  - Cross-customer access → 422 `FORBIDDEN_OWNER`.

## Phase 6: Payments, closure, loan extension, EMI (weeks 6–8)

**Payments:**
- The manual flow is reused unchanged (`RepaymentService` record → verify → reject).
- **New `PaymentAllocator`:** waterfall order is penal → principal (interest is already collected), as config `payments.waterfall`. It writes `payment_allocation(payment_id, component, amount_paise)` (V101).
- **Idempotency:** `idempotency_key(key PK, scope, response_hash, created_at)` (V101), applied to repayment submit, extension/EMI activation and future gateway webhooks.
- **`PaymentGatewayPort`** + `/api/webhooks/payments/{provider}` stub with no implementation, behind the `payment-gateway` flag.
- **Closure:** on CLOSED, render `CLOSURE_RECEIPT`, send a notification, and create a refund case if overpaid (reuse the navix overpayment register → `refund_case` table, V101).

**Schedules (V102):**
- `repayment_schedule(id, loan_id, version, kind BULLET|EXTENSION|EMI, parent_version_id, status ACTIVE|SUPERSEDED, created_by, reason_code)`.
- `schedule_instalment(schedule_id, seq, due_date, due_at, principal_paise, interest_paise, fee_paise, gst_paise, paid_paise, status)`.
- Every loan gets a v1 BULLET schedule at disbursal. The original is immutable.

**Loan extension: collections-led** (user decision 2026-10-27; redesigned 2026-10-28 from a line-by-line read of navix `main`; flag `loan-extension`, ON at launch subject to Legal/tax sign-off). File references below are to navix `main` (`C` = `navix-collections/.../collections`, `L` = `navix-loan/.../loan`, `M` = Flyway migrations).

*Product rules*

| Rule | Value |
|---|---|
| Who starts it | Only collections, after a borrower call. No borrower "extend" button |
| Eligible when | Loan lifecycle is `DUE_TODAY`, `OVERDUE_D1` or `OVERDUE_REVIEW`, and none of the following hold: a hold (`DISPUTED`, `FRAUD_REVIEW`), a repayment in `PENDING_VERIFICATION`, an open (PROPOSED or APPROVED-unpaid) settlement, a NACH debit in flight (`MandateDebitRepository.existsByLoanIdAndStatusIn`), restructure, or activated extensions ≥ cap |
| Cap | `product_config.extension_max` = 3. A 4th needs an ADMIN override with a new permission `collections:extension-override` and a reason code. Navix ADMIN has no `collections:*` permissions today (`rbac.ts:236-254`) |
| Charge | From the loan's pricing snapshot: fee 10% + GST 18% on the fee + principal × 1% × 14, plus late charge accrued up to the payment's bank `paid_on` (2%/day on principal; the 30-day cap counts penal days over the loan's whole life). Example: ₹1,000, 3 days late = ₹318 |
| Quote | Pure function `ExtensionQuote.of(loan, paidOn)` using the `LoanMath` helpers. Pay-by = the officer-chosen date. The offer expires at 23:59:59 IST on pay-by |
| New due date | Previous due date + 14, 23:59:59 IST. The late-charge window restarts from the new due date |
| Money model | `outstanding(asOf) = principal − verified REPAYMENT principal + late charge of the current window`. Interest is prepaid at disbursal (DhanNow engine), so moving the due date never adds interest by itself. Extension money is never in the "verified" sum. This fixes the navix `RepaymentService.breakdown` (L:322-343) coupling the backend audit found |
| Credit level | A loan with any activated extension closes with reason `EXTENDED_NO_CHANGE` |

*State machine* (`loan_extension.status`)

- **Main path:** `OFFERED` → `APPROVED` → `ACCEPTED` → `PAYMENT_PENDING` → `ACTIVE`.
- **Side exits:**
  - `OFFERED` → `REJECTED` (Head) or `CANCELLED` (officer);
  - `APPROVED` / `ACCEPTED` → `EXPIRED` (sweep at 00:05 IST after pay-by; also treated as expired on read) or `CANCELLED` (officer, or automatically when the loan becomes ineligible or is closed);
  - `ACCEPTED` → `DECLINED` (borrower "Not now" / "I didn’t ask");
  - `PAYMENT_PENDING` → `ACCEPTED` (payment rejected, retry until pay-by).
- **Guard on every transition:** an allowed-transition map plus the loan advisory lock (`MandateDebitRepository.lockLoan`, the same lock NACH uses, L/repository:65) plus `@Version`. Illegal transitions return 409 `STALE_STATE` with the current state.

*Backend changes (exact hooks in navix `main`)*

1. **Migration** (DhanNow V103):
   - `loan_extension` table, columns as in the earlier draft plus `status`, `@Version`, `seen_by_borrower_at`, `declined_reason`;
   - `unique(loan_id, seq)` and a partial unique index of one non-terminal extension per loan, so a second concurrent offer fails at the database;
   - alter `collection_payment_kind_check` (M/V47:49-50) to add `EXTENSION`; add `collection_payment.extension_id`;
   - `payment.purpose` (`REPAYMENT`/`EXTENSION`, default `REPAYMENT`) + `payment.extension_id`, with a check constraint;
   - `loan.extension_count`, `loan.original_due_date`;
   - `loan_due_history(loan_id, seq, due_date, due_at, from_at, to_at)` so DPD and lateness per cycle survive the move.
2. **`CollectionPaymentKind`** (C/entity:8-27): add `EXTENSION`. `needsHeadApproval()` stays false; the Head approved the offer.
   - `CollectionPaymentService.raise` (C/service:85-127) for `EXTENSION` requires an `ACCEPTED` extension on that case and sets the amount from the server quote for `paidOn`. A mismatched amount is rejected with 422 `EXTENSION_AMOUNT_MISMATCH`, never silently accepted.
   - Fix the hard-coded kinds message at `CollectionsController:271`.
3. **`CollectionPaymentService.validate`** (C/service:174-208):
   - for `EXTENSION`, call the new `LoanDirectory.applyExtension(...)` instead of `creditCollectionPayment`;
   - take the loan advisory lock and re-read the status under it. Today's `ledgerPaymentId` null check is not concurrency-safe: no `@Version`, no lock anywhere;
   - add `@Version` to `CollectionPayment`.
4. **`LoanDirectory` port + `LoanDirectoryAdapter`** (common :98-106, L:233-249): new `applyExtension(loanId, extensionId, paidOn, txnRef, proofRef, source)`. In one transaction under the loan lock it:
   - writes the `payment` row (`purpose=EXTENSION`, VERIFIED, `payment_allocation` `EXT_FEE`/`EXT_GST`/`EXT_INTEREST`/`PENAL`);
   - closes the current `loan_due_history` row and opens the next;
   - sets due += 14 and `extension_count`++;
   - resets lifecycle to `ACTIVE` and clears `IN_COLLECTIONS`. `markInCollections` (L:208-225) is one-way today, so an extended loan would otherwise never get reminders (`PaymentReminderScheduler:63` scans only ACTIVE/OVERDUE);
   - writes a new schedule version, the `balance_adjustment` row, and outbox rows for the addendum PDF and notifications.

   This is the only code path that moves a due date.
5. **`RepaymentService`** (L):
   - `recordPayment` (:72-111) takes a `purpose`. The txnRef dedupe (:87-92) becomes purpose-aware: a UTR already used for one purpose fails with 422 `UTR_USED_FOR_OTHER_PURPOSE` instead of returning the other row. The `partial` flag is skipped for `EXTENSION`.
   - `verifyPayment` (:151-166) branches: `EXTENSION` → `applyExtension`; it refuses to revive a `REJECTED` row (existing bug at :155).
   - The sums at :311, :375-376 and `latestVerifiedPaidOn` (:477-482) filter `purpose=REPAYMENT`.
   - `breakdown` is rewritten to the money model above.
6. **New `LoanExtensionService`** (navix-collections) + endpoints in `CollectionsController`. The BFF proxy only forwards GET/POST, so endpoints are GET/POST only.

   | Endpoint | Role / rule |
   |---|---|
   | `GET /cases/{id}/extensions` | State, history and eligibility with a reason code |
   | `POST /cases/{id}/extensions` | Offer: COLLECTION_EXECUTIVE via `assertCaseAccessible`; the case is auto-opened if missing (`CollectionsService.openCase` is idempotent) |
   | `POST /extensions/{id}/cancel` | Officer |
   | `POST /extensions/{id}/approve` | COLLECTION_HEAD; proposer ≠ approver, copied from `SettlementService.approve` C:134-160; re-checks eligibility |
   | `POST /extensions/{id}/reject` | Reason picklist + note |
   | `POST /extensions/{id}/resend` | Re-sends the borrower notification, rate-limited |

   `SettlementService.propose` (C:111) refuses while an extension is non-terminal, and offering refuses while a settlement is open.
7. **Borrower endpoints:**
   - `GET /api/loans/{id}` gains `pendingExtension{id, status, quoteForToday, payBy, newDueDate, seq, cap}` and `extensions[]`;
   - `POST /api/loans/{id}/extensions/{extId}/seen` (sets `seen_by_borrower_at`, shown to the officer);
   - `/otp` uses `OtpVerifierPort.request` with the new purpose `LOAN_EXTENSION`, copying `ApplicationVerificationService` :2996-3027, with the mobile resolved server-side;
   - `/accept {otp, consentVersion}` is idempotent;
   - `/decline {reason}`;
   - payment reuses `POST /api/loan/{loanId}/repayments` with `purpose=EXTENSION, extensionId`.
8. **Schedulers:**
   - `ExtensionExpirySweep` 00:05 IST, idempotent.
   - The reminder scheduler needs no change once the status resets.
   - NACH (flag off, code kept): `MandateDebitScheduler.shouldPresent` (:147-155) and `DebitService.prepare` (:270-273, :323) count attempts only since the current due cycle. Activation is refused while a debit is in flight. A debit that lands after an extension is an ordinary REPAYMENT.
   - The single-instance scheduler issue (`SchedulingConfig` has no ShedLock) is fixed by the `scheduler_lock` table from Phase 3.
9. **Notifications:**
   - events `ExtensionOfferedEvent`, `ExtensionApprovedEvent`, `ExtensionAcceptedEvent`, `ExtensionPaymentRecordedEvent`, `ExtensionActivatedEvent`, `ExtensionExpiredEvent`, `ExtensionDeclinedEvent`;
   - types after `NotificationType.java:139`:

     | Type | Audience | Channels |
     |---|---|---|
     | `EXTENSION_TO_APPROVE` | Heads | IN_APP |
     | `EXTENSION_OFFERED` | Borrower | IN_APP + EMAIL + PUSH; SMS once DLT `DN_EXT_OFFER` is approved |
     | `EXTENSION_ACTIVATED` | Borrower | IN_APP + EMAIL + PUSH + SMS |
     | `EXTENSION_EXPIRED` / `DECLINED` | Assigned executive | IN_APP |

   - templates after `NotificationTemplates.java:331`;
   - the in-app row is written through the outbox in the same transaction as the state change;
   - the bell deep-link carries `loanId` plus a new category `EXTENSION` (borrower → extension screen; staff → `/staff/collections?open=<loanId>&tab=collections`).
10. **Reports:** add `purpose='REPAYMENT'` (or an explicit extension-income line) to every query that reads verified `payment`, so extension money is never counted as recovery:
    - `CollectionQueries` 38-39, 72, 83-84, 96-98, 118, 143, 207-208;
    - `MetricSql` 66-67, 162-163;
    - `ReportService` 300, 303, 335, 441, 474-475, 505-506, 532, 722, 749-754, 939-940, 1168-1169, 1203, 1361-1362, 1396;
    - `TransactionLedgerQuery` 71-79 (new row type `EXTENSION`).

    A guard test greps `from payment` / `join payment` and fails if a query has no purpose handling.
11. **Worklist:** extended loans stay visible. `listCollectible`'s +7 horizon (L:155) and `CollectionsWorklistQuery:81` include loans with `extension_count > 0`, and `upcomingWatchlist`'s case filter (C:548-553) no longer hides them. New sortable column `extState` + facet.

*Staff console (exact files; wireframe row G)*

| Wireframe | Change |
|---|---|
| G1 | `app/staff/collections/page.tsx`: `WorklistRow` (`applications.ts:4214`) + `toRow`, `SORT_KEYS`/`FILTER_KEYS`/`COLUMN_LABEL`, column count 19→20 (645, 781); facet on `extState`; DhanNow bucket cards |
| G2 | `components/staff/customer-360/collections-tab.tsx`: new `ExtensionSection` between Interactions and Payments (143-167), modelled on `SettlementSection` (315-349), gated `canInteract`, refreshing through `changed()` (84-91). Also: "Extensions n of 3" in the Collections field grid; "Requested extension" added to `OUTCOMES` (36-37); `RecordPaymentCard` gets kind `EXTENSION` through `COLLECTION_PAYMENT_KINDS` (`applications.ts:4305-4315`) with the amount locked to the quote and `extensionId` sent; settlement section disabled while an extension is open |
| G3 | `app/staff/applications/page.tsx` RoleQueues (COLLECTION_HEAD, :187): new `ExtensionApprovalQueue` in `collection-payments.tsx`, using `QueueShell` + a 10 s poll + the settlement `ConfirmDialog`/`isOwnSettlementProposal` pre-flight. Fix the shared spinner (one pending flag per row) and the settlement-only copy (201, 208-212) |
| G4 | Accountant: `RepaymentVerifyQueue` and `CollectionPaymentValidationQueue` show an "Extension" badge, required vs received for `paid_on`, and the new due date preview. **Widen `onDone` invalidation** (250) to `staff-repayments`, `staff-loan-out`, `customer-detail`, `collections-worklist` and `collections-case-by-loan`; today only the queue refreshes |
| G5 | `repayment-tab.tsx`: original vs current due in `RepaymentDate`, an Extensions table, a Kind column in Payments, and `buildLedger` (`loan-ledger.ts:53-82`) excluding extension money. KPIs (`repayment-tab.tsx:341-344`, `customer-earnings`) count REPAYMENT only. `loan-behaviour.ts` judges each cycle against `loan_due_history` |

Also:
- **Known bug:** `admin-log-payment.tsx:133` invalidates with a string key while the tab uses a number. Fix it while here.
- **Base:** staff UI work is done on navix `main` markup with the app_navix kit applied (see Phase 0).

*Borrower app* (row C):
- **Screens:** C12 confirm (OTP), C12b pay, C12c verified, C1b extended dashboard, D6 "Extensions used n of 3" + addenda, C2 overdue "call collections".
- **C12d:** defines every state (S1–S9).
- **Rendering rule:** the app renders **from server status only**. It refetches on open, on resume, on pull-to-refresh and on push arrival; the notification only rings the doorbell. Unknown enum values decode to `UNKNOWN` and render S9, never a crash. (The navix web crashes on unknown payment statuses at `repay/page.tsx:526-544` and `transactions/page.tsx:140-141`; the app must not repeat that, and a contract test covers it.)

*Website:* `repayment-policy.html` and `loan-information.html` get an "Extending your loan" section with the same numbers (W5/W6 parity tests).

*Extension corner cases* (each one is a test `EXT-xx`; together with CC-67…CC-71 they cover every actor × failure point)

| # | Situation | Decided behaviour |
|---|---|---|
| EXT-01 | Two officers offer on the same loan at once | DB partial unique index; the second gets 409 `EXTENSION_ALREADY_OPEN` with the first offer shown |
| EXT-02 | Offer on a loan that isn’t due yet / already closed / on hold / has a pending repayment / an open settlement / a debit in flight | 422 with a specific reason code; the panel shows the reason instead of the Offer button |
| EXT-03 | Officer not assigned to the case | 422 `CASE_NOT_ASSIGNED` (existing check); the panel says who owns it |
| EXT-04 | Head tries to approve their own offer (working as Executive) | Button replaced by "You offered this"; server 422 `SOD_VIOLATION` anyway |
| EXT-05 | Head approves after the pay-by day | Server refuses (offer expired) and the officer re-offers with a fresh quote |
| EXT-06 | Borrower repays in full, or the loan becomes ineligible, while an offer is open | The offer auto-cancels in the same transaction as the repayment verification; borrower sees S4; any extension payment goes to a refund case |
| EXT-07 | Borrower never opens the app | The offer expires at pay-by (S2 if opened later); the officer sees "not opened yet" and can Resend (max 3/day) |
| EXT-08 | Push not delivered (no FCM / notifications off) | The dashboard banner (S1) comes from the loan API, so the offer is visible on next app open regardless |
| EXT-09 | App open on an old screen when staff act | Refetch on resume; every money action carries the extension `version`; a stale submit gets 409 → the app re-renders the current state |
| EXT-10 | Borrower opens an old notification after things moved on | The screen is chosen by current server status (S2/S3/S4); notification text is never trusted |
| EXT-11 | OTP not received / wrong 5× / lockout | Existing OTP limits; S5; the offer stays valid until pay-by |
| EXT-12 | App killed after OTP verified, before the response | Accept is idempotent; the retry returns ACCEPTED |
| EXT-13 | Borrower declines or says "I didn’t ask" | `DECLINED` + reason; the officer is notified; "I didn’t ask" also opens a complaint (CC-71) |
| EXT-14 | Borrower pays the extension through the normal Repay screen instead of C12b | Repay shows a banner while an extension is ACCEPTED: "Pay ₹318 to extend, or ₹1,060 to repay in full". The Accountant can "Treat as extension payment" when the amount matches (audited) |
| EXT-15 | Borrower pays and the officer also logs the same payment | Same UTR → one payment (purpose-aware dedupe). Different UTRs → the second goes to a refund case; only one payment can activate an extension |
| EXT-16 | Wrong amount | Less → "Count as part payment" or reject. More → extend + refund case for the excess. Never auto-extend on a short payment |
| EXT-17 | Paid on time, verified after pay-by | Allowed: the quote and on-time status follow bank `paid_on` (S6 copy says so) |
| EXT-18 | Paid after pay-by | The quote is recomputed for that `paid_on`. If short → EXT-16. The offer may already be EXPIRED → the Accountant gets "Re-open for this payment" (Head approval required) or refund |
| EXT-19 | Payment rejected | The borrower sees S7 with the reason and can resubmit until pay-by; state returns to ACCEPTED |
| EXT-20 | Two accountants validate at once / double click | Loan advisory lock + `@Version` → the second gets "Already verified by … at …"; no double date move |
| EXT-21 | Scheduler runs mid-verification (reminder, expiry sweep, NACH) | All take the same loan lock; the expiry sweep skips `PAYMENT_PENDING`; a debit in flight blocks activation (EXT-02) |
| EXT-22 | Addendum PDF or notification fails after activation | Activation commits; the outbox retries PDF and notifications with backoff; the borrower sees "Extension letter being prepared"; an alert fires after 3 failures |
| EXT-23 | Activation succeeds but the staff console still shows the old due date | Widened invalidation (G4) + 8 s polls; the pop-up re-reads after any mutation |
| EXT-24 | Borrower’s app version predates extensions | `/app/config` `minVersion` forces an update; an older app never shows the banner, and S9 covers malformed data |
| EXT-25 | Cap reached | Offer button replaced by "Cap reached: repay, settle or ADMIN override"; the override needs `collections:extension-override` + a reason and is audited |
| EXT-26 | Late charge 30-day cap across cycles | Lifetime cap on penal days per loan; the quote shows ₹0 late charge once reached |
| EXT-27 | Extension and dispute | A dispute raised while an extension is open cancels the offer (not an ACTIVE extension); offers are blocked while disputed |
| EXT-28 | Loan behaviour / credit level | Per-cycle DPD from `loan_due_history`; any activated extension → no level upgrade; reports show lateness per cycle |
| EXT-29 | Day boundary | All dates are IST via `Clock`; pay-by ends at 23:59:59 IST; the sweep runs at 00:05 so no request races the boundary |
| EXT-30 | Reports and dashboards | The guard test fails if any `payment` query lacks purpose handling; extension income has its own series and ledger type |

**EMI** (flag `emi-conversion`; V103 `emi_conversion`):
- COLLECTION_EXECUTIVE / COLLECTION_HEAD *requests*; **ADMIN only approves** (RBAC server-side).
- The customer accepts in the app.
- Instalments: ≤₹5k → days 10/20; ₹7k/₹10k → days 10/20/30. Day 0 is the conversion date.
- Remainder paise go to the last instalment.
- Lifecycle → RESTRUCTURE_PENDING → RESTRUCTURED.
- EMI interest = config `emi.interestMode` (`NONE` default).

**Tests:**
- Extension cannot activate on a pending, short or failed payment; only Accountant verification moves the due date.
- Extension quote for ₹1,000 paid 3 days late = ₹318; new due = old due + 14; 4th extension without ADMIN override → 422 `EXTENSION_CAP_REACHED`; officer cannot approve own offer → 422 `SOD_VIOLATION`; extended loan closes with no level change; principal ledger untouched by extension payments.
- Cap reached → 422.
- ₹7,000/3 sums exactly.
- An EMI approved by a non-ADMIN → 422.
- Webhook replay is idempotent.

## Phase 7: Grievance, disputes, audit (week 8)

- **V104 `complaint`:** `ticket_no` (DN-GRV-xxxxx), `customer_id`, `loan_id`, `category`, `body`, `attachment_key`, `status` (`OPEN` | `ACKNOWLEDGED` | `IN_REVIEW` | `RESOLVED` | `ESCALATED`), `ack_due_at` (+2 working days), `review_due_at` (+5), `resolve_due_at` (+15 calendar), `assigned_to`.
  - SLA sweep sends notifications and flags breaches.
  - Borrower API: `POST/GET /api/complaints`.
  - Staff queue.
- **Dispute:** `POST /api/loans/{id}/dispute` (staff, or from a complaint category) sets `hold_status=DISPUTED`.
- **V105 `balance_adjustment`:**
  - Columns: `loan_id`, `customer_id`, `kind`, `before/after jsonb`, `schedule_version_from/to`, `calc_version`, `approver_id`, `reason_code`, `acceptance_evidence`, `reconciliation_id`, `at`.
  - Written by every money or schedule change: settlement, extension, EMI, refund, manual correction.
  - Includes a `reason_code` lookup table.
- **Roles:** add `RECOVERY`, `SUPPORT`, `FINANCE` and `COMPLIANCE` to `StaffRole` (V106 role check).
  - COMPLIANCE is read-only.
  - SUPPORT cannot touch balance or due date.
  - Mirrored in `web/src/lib/auth/rbac.ts`.
- **MFA for staff:** TOTP (`staff_totp_secret`, encrypted). Required for ADMIN, FINANCE, CREDIT_HEAD and DISBURSEMENT_HEAD.

## Phase 8: Mobile-ready auth and platform (weeks 8–9)

**Refresh tokens and logout:**
- V107 `refresh_token(id, customer_id, device_id, token_hash, expires_at, revoked_at)`.
- Access JWT TTL 15 min, refresh 30 days with rotation and reuse detection.
- New `POST /api/auth/borrower/refresh` and `/logout`. The web BFF keeps its cookie but uses refresh too.

**Play Integrity:**
- `CaptchaVerifier` gains an `IntegrityVerifier` branch.
- When header `X-Client: android` + `X-Integrity-Token` is present, the backend verifies it with the Google Play Integrity API (package `com.dhannow.app`, `MEETS_DEVICE_INTEGRITY`) instead of Turnstile.
- Flag `play-integrity`.

**OTP and limiter state to Postgres:**
- V108 `otp_challenge(id, purpose, subject_hash, code_hash, expires_at, attempts, sent_count_window, blocked_until)` and `attempt_counter(key, window_start, count, blocked_until)`.
- `BorrowerOtpService`, `EmailOtpService` and `AttemptLimiter` are switched behind their existing interfaces.
- Server-side caps added where only the web enforced them:
  - selfie 3 attempts;
  - DigiLocker 3 tries;
  - the signup penny drop routed through `PennyDropGuard`.

**Push:**
- V109 `device_token(customer_id, device_id, fcm_token, platform, app_version, last_seen)`.
- `PUT /api/devices/me`, `DELETE /api/devices/me/{deviceId}`.
- `FcmSender` as the 5th `NotificationChannel`, behind flag `push-fcm`.

**App links:**
- `navix.app.frontend-base-url` → `https://dhannow.com`.
- Serve `/.well-known/assetlinks.json` from web.
- DigiLocker/e-sign redirect URLs must be on an **allowlist** (`https://dhannow.com/app/cb/*`); new validation is added, since navix has none.
- `JourneyView` adds `stepKey` so the app maps steps itself; the web `route` is kept.

**Liveness:**
- `POST /verify/selfie/frames` accepts 3 object keys (straight/right/left) → status `REVIEW`, and the case goes to a staff "Selfie review" queue (approve/reject + reason).
- `LivenessPort` interface with a `ManualReviewLiveness` impl, so Digitap or ML Kit can be added later.

**CORS:** allowlist `dhannow.com` and `staff.dhannow.com`.

## Phase 9: Hardening (week 9, parallel with Phase 8)

- **PII encryption:** `PiiCipher` (KMS data key + AES-GCM, JPA `AttributeConverter`) on Aadhaar, PAN and account numbers.
  - V110 backfill is not needed (fresh DB), but the converter tolerates plaintext for the migration window.
  - Staff DTOs show masked values unless the user has the `PII_VIEW` permission (audited).
- **Seeded passwords:** strip them from V17/V19 copies. Fresh DB, so these migrations are edited **before first deploy**, which is acceptable because no DhanNow DB exists yet. Then `BootstrapAdminRunner` creates the first admin from the one-time SSM secret `/dhannow/<env>/bootstrap-admin` and refuses to run again.
- Remove the "Act as role" switcher in prod.
- Edge middleware verifies the JWT signature.
- Foreign keys added on the new tables (existing tables untouched).

## Phase 10: Staff console fork (weeks 9–11)

The web side has three parts:

| Part | Where | Notes |
|---|---|---|
| Public website | `dhannow.com` | The existing static site in `dhannow/website/` |
| Borrower product | Android app only | No borrower web login in v1, matching what the website already says |
| Staff console | `staff.dhannow.com` | Forked from navix |

- **Strip the fork to staff only:** delete `web/src/app/(borrower)`, `(marketing)`, the borrower BFF routes (`app/api/auth/borrower/*`, `app/api/borrower/*`) and the borrower-only components and `lib` code (`components/borrower`, the borrower APIs in `lib/api/applications.ts`).
  - The staff middleware becomes the root.
  - ArchUnit-style lint rule (eslint `no-restricted-imports`) prevents borrower code coming back.
  - Backend borrower endpoints stay (the app uses them). CORS allows only `staff.dhannow.com`.
- **Brand:** `web/src/lib/brand.ts` → DHAN NOW. Theme tokens → app_navix kit with the cobalt accent. Logo. Remove the placeholder NBFC names.
- **Staff console additions:**
  - disbursal-confirmation queue (Accountant);
  - selfie review queue;
  - loan extension: offer, Head approval, Accountant verification, history (wireframe row G, see Phase 6);
  - EMI request + approval;
  - disputes;
  - complaints with SLA;
  - credit-level view + manual exception;
  - pricing/product config (view, draft, approve; ADMIN + FINANCE maker-checker);
  - balance-adjustment log (Compliance read-only);
  - MFA enrolment.
  - Update `staff-nav.ts` and `rbac.ts`.
- Split `lib/api/applications.ts` (4,906 lines) only where new code touches it. No big refactor.

### Phase 10a: Website ↔ app wiring (`site/` = today's `dhannow/website`, weeks 9–10)

The 24 static pages move into the monorepo as `site/`, unchanged except for the items below. Hosting: S3 + CloudFront on `dhannow.com`, HTTPS, deployed by CI.

| # | Wiring | Detail |
|---|---|---|
| W1 | Legal pages opened from the app | One `legal_documents` config (backend `GET /api/app/config` → `legal: {key, title, url, version}`) maps app screens to site pages. The app opens them in a Custom Tab: TermsModal (A1b) → `terms.html`; Consent (A11) → `consent.html`; Trust (D4) → `privacy.html`, `fair-practices.html`; Support (D3) → `faq.html`, `grievance.html`, `contact.html`; Settings "Terms, privacy and fair practices" → `terms.html`, `privacy.html`, `fair-practices.html`, `cookie-policy.html`; Repay (C6) → `repayment-policy.html`, `payment-refund-policy.html`; KfsView (B5b) → `kfs-policy.html`; Delete account (D8) → `privacy.html` (retention section) |
| W2 | Versioned consent | Every legal page carries `<meta name="dn-doc-version">`. When the user accepts Terms, Consent or KFS, the app sends `{key, version}` and the backend stores it on the existing T&C/consent records (navix `POST /verify/agreement {versions}` reused). A CI check fails if a page changes without a version bump |
| W3 | App links + callbacks | `site/.well-known/assetlinks.json` (Play signing SHA-256). `site/app/cb/digilocker.html`, `site/app/cb/esign.html` as tiny fallback pages that open the app (`intent://`) or tell the user to return to the app. These are the only allowlisted provider redirect URLs (Phase 8) |
| W4 | "Get the app" | Until the Play listing exists: `site/download.html` serves the signed APK from S3 with SHA-256 and install steps. After the listing: Play Store badge + link. Both toggled by one config value. Website copy ("apply in the Android app", "no login on the website") is kept and checked against this |
| W5 | One source for contacts | Brand, support, grievance, CIN and registered office come from one `docs/brand.json`. A build step injects it into `site/` pages, backend `navix.brand.*` / `navix.grievance.*` defaults and the app's l10n constants. A test fails if any copy differs |
| W6 | Calculator parity | `site/calc.js` (already DhanNow 14-day maths, with asserts) is the public calculator. A CI test runs it for all 7 ladder amounts and compares with backend `LoanMath` output, so the website, app and PDFs never disagree |
| W7 | KFS / sanction / agreement sample pages | `kfs.html`, `sanction-letter.html`, `loan-agreement.html` stay as public samples, marked "illustrative". Real documents come only from the backend PDFs. The lender placeholder on these pages is filled from W5 config and blocked by the same prod guard |
| W8 | Deep links from the website | `dhannow.com/app/open?to=repay|loans|support` opens the app screen if installed, otherwise goes to `download.html`. Used in SMS and email templates instead of web routes (replaces navix `/login?next=`) |
| W9 | Theme parity | `site/` already uses the blue palette (`#1F5BDB`, `#EEF3FF`). It is checked against the F2 tokens by a small CSS-variable diff in CI |

**Wireframe follow-up (done 2026-10-27):** the legal links on A1b, A11, B5b, C6, D2, D3, D4 and D8 are labelled with the dhannow.com page each one opens. New boards E15 (return-to-app page, `app/cb/*`) and E16 (`download.html`) are added.

**Tests:** link checker over `site/` (no 404s, every app-referenced URL exists). Playwright smoke on `site/` (calculator values, download page). App integration test opens each legal link (URL asserted, not content).

### Phase 10b: Staff dashboards, customer 360, loans, collections, accounting, reports (weeks 9–11)

The audit found about 120 places where staff screens, queries and exports encode navix rules. Fix the **shared roots first** so most screens correct themselves, then the per-surface items.

**Shared roots (backend + `web/src/lib`):**

| # | Location | Fix |
|---|---|---|
| R1 | `LoanMath` | Upfront interest, principal-only repayable, 14-day due. Phase 1 |
| R2 | `LimitCalculator`, `EligibilityService:47,70`, `ApplicationFlowService:271`, `MandateService:566-569` | Ladder |
| R3 | `LoanService.disburse` | Confirmation-gated, IST `Clock` |
| R4 | `RepaymentService.breakdown` (318-342) | `interestPrepaid` is returned for display and **excluded from owed**. Owed = principal + penal − paid. Every staff money figure flows through this |
| R5 | `Loan.effectiveStatus`, `LoanStatus`, `web/lib/domain/loan.ts` | Mirror the new lifecycle + hold enums exactly (the frontend enum is already out of sync with the backend in navix) |
| R6 | Accountant | Revive disbursal confirmation (Phase 4) |
| R7 | `web/lib/calc/loan-math.ts` | Remove `dueDateFromSalary`. Mirror R1 for display only |
| R8 | `web/lib/calc/loan-ledger.ts` | Rows: principal, penal, interest = "prepaid at disbursal". Payment split penal → principal |
| R9 | `web/lib/customers/customer-360.ts` | Limit basis = ladder level / admin exception. Drop the 40-day window |
| R10 | Bucket definitions | **One** shared definition (backend `DpdBuckets` + `web/lib/collection-buckets.ts` generated from it) replaces the six copies (`DpdCalculator`, `CollectionsWorklistQuery`, `MetricSql`, `ReportService`, `loan-math.ts`, `collection-buckets.ts`). Buckets: DUE_TODAY, D1, D2–7, REVIEW 8–30, 31–60, 61–90, 90+. RBI SMA/NPA classes kept alongside |
| R11 | `cells.tsx:23`, `applications.ts:283`, `sanction-dialog.tsx:62` | All date maths uses an IST helper, never browser-local `new Date()` |

**Revenue recognition** (finance-critical):
- Interest income is booked **with fee and GST on the disbursal-confirmation date**. Today it is booked at closure in `DashboardService.daily`/`BusinessQueries:110-127` and `ReportService.incomeRealised:1389`; that has to change.
- Penal stays booked on receipt.
- A new reconciliation invariant is checked nightly and in a test: `principal = net + fee + GST + interest`.

**Per-surface changes** (each gets an updated vitest + a Playwright assertion of the ₹10,000 example: net ₹7,420, repay ₹10,000):

| Surface | Change |
|---|---|
| Sanction dialog | Remove the salary-day picker. Amount chosen from ladder chips ≤ the customer level. Breakdown shows fee / GST / interest-upfront / net / repay = principal. "Due 14 days after confirmed disbursal, 23:59 IST". Release goes to accountant confirmation |
| Loan breakdown / stage popups / admin-log-payment | Interest shown as a prepaid deduction. Remove "interest accrued" and the salary wording. Hold states block payment logging only for FRAUD_REVIEW |
| Customer 360 | Loan card and loan tab: new status badge map, no "1% per day accrued", upfront-interest rows, "Confirmed by/at". Repayment tab: remove "pay early & save"; ledger per R8; DUE_TODAY state. Customer tab: ladder level + history + next level replace "Eligible limit 25%". **Delete** `SalaryDayCard` + endpoint (`CustomerService:1479`). `LimitOverrideCard` becomes a ladder-level exception (ADMIN, reason, audit). Collections tab: hold banner + controls. Mandate tab: "eNACH disabled" state |
| Customers list + segments | New segments (front and back: `segments.ts`, `CustomerSegments.java`, `CustomerBookQuery`): Due today, D1, Overdue review, Restructure, On hold, Awaiting disbursal confirmation |
| Pipeline / queues / live applications | Accountant desk gets a **Disbursal confirmation** queue. Repayment book gets Due today / D1 / Review / Restructure / Holds columns. Remove "interest is accruing" copy. New desks for RECOVERY, SUPPORT, FINANCE, COMPLIANCE |
| My decisions | Repayment-date column → amount, level, "due 14d after confirmation" |
| Loans register | Columns: principal, fee, GST, interest (upfront), net, repayable (= principal), confirmed on/by, lifecycle, hold. New status filters and segments |
| Collections | Pre-due window re-tuned for a 14-day tenure (config, default 2 days, not 7). Remove salary columns. Settlements: gross due = principal + penal, so "loss" is no longer overstated by interest (`LoanDues`, `SettlementService:337`). New queues: OVERDUE_REVIEW, Restructure (EMI request/approve + instalment ledger), Extensions (offer / approve / verify, G1–G4), Holds |
| Accounting transactions | Ledger rows: DISBURSAL (net), FEE income, GST payable, INTEREST income (at confirmation), REPAYMENT, PENAL income, REFUND. Disbursal confirmation status column. FINANCE role access |
| Dashboard (all role views) | Receivable = principal (`MetricSql:129`, `DashboardService:151,240,263`). `total_repayable` sums → principal everywhere in `MetricSql`/`BusinessQueries`/`CollectionQueries`. Business snapshot copy rewritten (₹100 → ₹74.20 in hand). New "Interest earned upfront" card. Pre-closure gauge removed. New role views in `views.ts` + `DashboardScope` |
| Performance | Collection-efficiency denominator = principal + penal (`ScoreFormulas:87`). Scorecards for the new roles |
| Reports | Rewrite `ReportService` guide/lifecycle text (358-410). Positions, disbursals, closed-loan timing (23:59 IST, grace 0), dues, efficiency, vintage and income all use the new rules. FINANCE and COMPLIANCE get the Finance & tax / Compliance & audit categories |
| New finance views | Upfront-interest income register, GST payable register, net-disbursal reconciliation, refunds owed, credit-ladder distribution |
| Exports | `customer-export.ts`, `collection-handover-columns.ts`, inline `ExportMenu` columns: ladder level instead of eligible limit/salary day; dues = principal + penal; hold/restructure status. `CreditBriefPdfRenderer`: rebrand only |
| Documents presentation | Staff "View KFS / sanction / agreement" opens the same stored PDFs the borrower saw (`loan_document`), never a re-render. Figures on staff screens are read from the same frozen snapshot, so screen, PDF and ledger always agree (test asserts equality) |

**Guard test:** a repo-wide test fails the build if any staff or borrower source contains "per day for the days held", "salary day", "25% of", "pay early", "save interest" or "DhanBoost" outside an allow-listed legacy folder.
- **Tests:** vitest for new components; Playwright e2e for borrower happy path + staff sanction → disburse → confirm → verify repayment, run in CI against docker-compose.

## Phase 11: Flutter app (`app/`, weeks 5–12, parallel from Phase 5)

**Started early (2026-11-01), design-system layer only:** `app/` scaffold (`com.dhannow.app`, minSdk 24, R8 + resource shrinking), bundled Inter + Bebas Neue subset to Latin + ₹, `core/theme`, and the `Dn*` widget kit in `core/widgets` matching `docs/wireframes/ui-kit.html`, plus a dev-only kit gallery. It needs no API, so it does not wait for Phase 5. Widget tests cover the kit at 320/412dp × 1.0/1.3/2.0 text scale (CC-58). Journey screens still follow the build order below, once their APIs exist.

**Journeys built against a demo server (2026-11-01, owner asked to see the whole app):** every board S–E is a screen now, under `lib/features/`, routed by `go_router` (`core/router.dart`). Data comes from `core/api/api.dart`, an in-memory demo server with one method per planned endpoint and a scenario picker (`/demo`, from Settings or a long press on the Welcome logo) to show every journey state. Its quotes mirror `LoanMath` (₹1,000 → ₹742). When each phase's endpoints land, the method bodies become `dio` calls (and riverpod comes in then if still needed); screens do not change. Contacts come from `core/brand.dart`.

**Packages** (kept to these, each justified):

| Package | Why |
|---|---|
| `go_router` | Declarative routes + deep links |
| `flutter_riverpod` | Small DI/state with no codegen |
| `dio` | Interceptors for auth refresh and the envelope |
| `flutter_secure_storage` | Refresh token |
| `local_auth` | Biometric unlock |
| `firebase_core` + `firebase_messaging` | Push |
| `camera` | Selfie frames + document capture |
| `file_picker` | PDF payslips |
| `flutter_custom_tabs` | DigiLocker, e-sign, Digio |
| `app_links` | Callbacks |
| `geolocator` | Consent-time location |
| `play_integrity`, via a thin platform channel | No heavy plugin |
| `intl` + `flutter_localizations` | ARB |
| `url_launcher` | Call, mail |

**Not added:** bloc, freezed / json codegen (hand-written models from OpenAPI, see below), GetX, Hive / any local DB, Lottie, analytics SDKs beyond Firebase core.

**Budget:** release APK under 12 MB per ABI (`--split-per-abi`, R8, shrink resources).

**Structure:**
```
app/lib/
  core/   (api, auth, theme, router, l10n, widgets: StepScaffold, AppButton, Field, StatusChip, BackConfirm, ErrorView)
  features/
    start/  onboarding/  offer/  dashboard/  repay/  loans/
    extension/  emi/  profile/  support/  complaints/  notifications/
```

**API layer:**
- springdoc already serves `/v3/api-docs`. A CI job exports `openapi.json`, and a small Dart generator script (`tool/gen_api.dart`, ~200 lines) produces typed request/response classes for the endpoints the app uses.
- **Contract test:** CI fails if the generated file differs from the committed one.
- `ApiEnvelope<T>` unwraps `data` / `error.code`. The error code → user message map lives in one place.
- 401 → refresh once → otherwise SessionExpired (board E5). Network errors → Offline (E3). 5xx → ServerError (E4) with a request ID.

**Rules from the wireframe (board E notes):**
1. Every step that called an API uses `StepScaffold` with `PopScope` → BackConfirm sheet (E1).
2. Completed steps reopen read-only (E2).
3. Primary buttons lock with a spinner while a request is in flight.
4. Every error has a retry + a way out.
5. The app always opens at `GET /journey` (E6).
6. Support is reachable when logged out.

**Build order mapped to boards:**

| Order | Boards |
|---|---|
| 1 | S1–S6 |
| 2 | A1–A14 |
| 3 | B1–B12 + KfsView + Selfie |
| 4 | C1–C12 incl. Extension (C12, C12b, C12c, C1b), EMI, Closure |
| 5 | D1–D8 incl. Complaint, Loan account details, Devices, Delete account |
| 5b | F4 milestone animations: timeline, money on the way, level-up, dashboard ring. Golden tests at frame 0 and the final frame, plus a reduced-motion variant |
| 6 | E1–E14 |

**Release:**
- Upload key in GitHub secrets, Play App Signing, internal → closed → production tracks.
- Crash reporting via Firebase Crashlytics (only on top of `firebase_core`).
- `assetlinks.json` SHA-256 from the Play signing key.

## Phase 12: Testing strategy (continuous, gate per phase)

| Layer | Tooling | What must pass |
|---|---|---|
| Money math | JUnit + jqwik | 7-level table, invariants, rounding, APR |
| Services | JUnit + Mockito (existing style) | Ladder, lifecycle transitions, SoD, extension/EMI rules, allocation |
| Integration | Testcontainers Postgres 16 (existing `-Pit`) | One IT per new flow end to end, with a fixed `Clock`, mocked providers (existing mocks: eSign, Digio, SMS mock, bureau fixture) |
| Architecture | ArchUnit | No module cycles; money math only in `navix-loan` |
| Contract | OpenAPI diff + generated Dart | No unreviewed API change |
| Web | vitest + Playwright (now in CI) | Staff critical paths; `site/` link check + calculator parity |
| App | `flutter test` (unit, widget, golden for each board's key state) + `integration_test` on an emulator in CI against a docker-compose backend | Full first-time journey: signup → approved → offer → selfie → e-sign mock → disbursed → repay → closed → L2 |
| Load | k6 script | 100 rps on the journey/dashboard endpoints, p95 < 400 ms on db.t4g.medium |
| Security | OWASP ZAP baseline on staging, `dependency-check`, `flutter analyze`, `npm audit` | No high findings |

Coverage gates: engine packages ≥ 80%, app `core/` ≥ 70%.

## Phase 13: Infra, CI/CD, go-live (weeks 11–13)

- **`infra/terraform`:** VPC, NAT (new static egress IP → Digitap allow-list), ALB + ACM HTTPS for `api.dhannow.com`, S3 + CloudFront + ACM for `dhannow.com` (static `site/`, APK download, `.well-known/assetlinks.json`) and the staff console host `staff.dhannow.com` (Vercel or CloudFront; staff-only), ECS Fargate service, RDS Postgres 16 (pinned to match tests; navix's 16/18 drift is avoided), S3 + KMS, SSM `/dhannow/{staging,prod}/*`, SES domain `dhannow.com` + SNS/SQS, CloudWatch alarms.
  - **Two environments:** staging and prod (fixes navix's "prod reads dev").
- **`.github/workflows`:** `backend.yml` (unit, IT, JaCoCo, ArchUnit, OpenAPI export), `web.yml` (tsc, lint, vitest, Playwright, deploy Vercel), `app.yml` (analyze, test, contract check, integration_test on emulator, build AAB, upload to the Play internal track on tag), `deploy-backend.yml` (OIDC, image by SHA, migrate, health check). No hardcoded navix account IDs.
- **Go-live checklist (blocks prod), external items you must supply:**
  - lender legal name / address / RBI registration;
  - DLT re-registration (sender ID + all templates worded "DHAN NOW");
  - Signzy, Digitap, Fintrix, Digio and SES accounts or allow-listing for the new stack;
  - Play Console app + upload key;
  - Firebase project;
  - Legal sign-off on upfront interest, APR method, KFS and agreement text;
  - Legal and tax sign-off on the loan extension (re-charging fee + GST + interest on the same principal, the 3-extension cap, addendum text);
  - grievance officer confirmation.

  The prod config guard enforces lender + grievance + DLT IDs.

## Corner-case catalogue (each item = decided behaviour + a named test)

Each row becomes a test named `CC-xx` in the module shown. A phase isn't done until its CC tests pass.

**Schema and API additions these cases need** (folded into the phase migrations; numbers assigned in order after V110):
- `customer_id` sequence `DN0000001`, used by the auth signup path (Phase 8);
- `loan_application.offer_valid_until` (Phase 5);
- `loan.closure_type` (`REPAID` | `SETTLED` | `WRITTEN_OFF` | `REVERSED_REOPENED`) (Phase 6);
- unique index on normalised `payment.txn_ref` (Phase 6);
- `POST /api/staff/payments/{id}/reverse` (Phase 6);
- required `Idempotency-Key` header (Phase 6);
- `holiday_calendar` (Phase 7);
- `account_deletion_request` + retention purge job (Phase 9);
- `GET /api/app/config` (Phase 8);
- per-IP/device limiter keys (Phase 8).

### A. Money and time (navix-loan)

| # | Case | Behaviour |
|---|---|---|
| CC-01 | Disbursal confirmed on a different day than the offer (after the 18:00 cutoff, weekend, accountant delay) | Day 0 = **confirmed** date. KFS and sanction letter state "14 days from disbursal" plus an indicative date. The final due date is fixed at confirmation, written to schedule v1 and the `SCHEDULE` doc, and sent by SMS/push |
| CC-02 | Offer validity lapses before e-sign | Offer expires (`offer_valid_until` from config, default 3 days). Re-quote with the current pricing → new KFS → re-acknowledge. An old KFS can never be signed |
| CC-03 | Pricing config activated while offers are open | Open offers keep their frozen snapshot until expiry. New offers use the new version. Admin UI shows the count of open offers before approving |
| CC-04 | Credit level changes between sanction and disbursal | Amount is locked at sanction; level changes apply to the next loan only |
| CC-05 | Paid on day 14 at 23:50 but verified the next day | On-time is judged by `paid_on` (bank credit time confirmed by the accountant), **not** `verified_at`. Penal accrued in between and the OVERDUE state are reversed on verification, through `balance_adjustment`. Ladder upgrade still happens |
| CC-06 | Borrower enters a future or very old `paid_on` | Future dates rejected. Backdating limited to 3 days (navix rule, kept). The accountant can correct it with a reason code |
| CC-07 | Same UTR submitted twice, or on another customer's loan | UTR is unique globally (DB unique index on normalised `txn_ref` across `payment`). Second submit → 422 `DUPLICATE_UTR` |
| CC-08 | Overpayment or payment to a CLOSED loan | Excess goes to a `refund_case` (FINANCE). Never auto-applied to a future loan |
| CC-09 | Part payments | Allowed. Waterfall penal → principal. Loan stays open. `repayment_status=PARTIAL`. Only full on-time closure upgrades the level |
| CC-10 | Verified payment later found bounced/reversed after closure | `POST /payments/{id}/reverse` (FINANCE + ADMIN, maker-checker) reopens the loan to the state its dates imply, reverts any level upgrade with a history row, and is audited |
| CC-11 | Manual payment pending + NACH presentment due (when eNACH is later enabled) | Presentment is skipped while `PAID_PENDING_VERIFICATION`, `DISPUTED` or `RESTRUCTURE_PENDING`. If both clear → automatic refund case. Tested with the flags forced ON in the IT |
| CC-12 | NACH timing (code ready, flags OFF at launch) | Present on the due date (config time, default 10:00 IST), with retries D+1/D+3/D+5. Mandate ceiling = top ladder ₹10,000 so level-ups don't need re-mandating. A failed debit never cancels the obligation. With the flags OFF: no mandate step, no presentment, Autopay board hidden, and the staff mandate tab shows "eNACH disabled" |
| CC-13 | Scheduler down for hours or days | Lifecycle job derives state from the **current time vs `due_at`**, not from "last run", so catch-up is correct. Penal is computed from dates, not run count |
| CC-14 | Two app instances run schedulers | Postgres advisory lock + optimistic `@Version` on loan → exactly one transition (IT with 2 threads) |
| CC-15 | Late charge and extension in the same period | Late charge up to the pay-by date is part of the extension amount and is not charged again; after activation the late-charge clock restarts from the new due date |
| CC-16 | Extension paid days after the due date | Allowed while the loan is overdue (no separate window). New due = **previous due + 14**, so a late extension buys fewer remaining days; the quote recomputes for the actual bank `paid_on` date |
| CC-67 | Borrower confirms extension but never pays | Quote expires at 23:59 IST on pay-by day → `EXPIRED`; nothing changes; officer can re-offer with a fresh quote |
| CC-68 | Extension payment arrives after the quote expired | Accountant verifies against a quote recomputed for the bank `paid_on` date; if short → part payment or reject (never auto-extend) |
| CC-69 | Borrower pays both the extension charge and the principal | Verified principal closes the loan; the extension (if not yet active) is cancelled and its charge goes to a refund case |
| CC-70 | Officer logs a payment and borrower also pays in app for the same extension | Partial unique index allows one verified payment per extension; second goes to refund case; UTR uniqueness (CC-07) also blocks a duplicate UTR |
| CC-71 | Borrower says "I never asked" | "Tell us" on C12 opens a complaint and cancels the pending extension; recorded call note + recording id are on the offer |
| CC-17 | EMI instalment missed | Instalment becomes OVERDUE. Penal per config `emi.penal` (default off). Loan stays RESTRUCTURED. A collection case is reopened |
| CC-18 | Settlement closes a DhanNow loan | Closed, `closure_type=SETTLED`, level never upgrades |
| CC-19 | Penal cap | Stops at 30 days. Outstanding never grows after that |
| CC-20 | Rounding | Ladder amounts give exact paise. EMI remainder goes to the last instalment. A property test asserts sum equality for every amount × instalment count |

### B. Concurrency and integrity (all modules)

| # | Case | Behaviour |
|---|---|---|
| CC-21 | Two staff verify, sanction or approve the same item | `@Version` optimistic locking on `loan`, `loan_application`, `payment`, `loan_extension` and `emi_conversion` → the second gets 409 `STALE_STATE` and a "refresh" message |
| CC-22 | Double-tap or network retry on a borrower POST | `Idempotency-Key` header required on money/state POSTs. The app sends one UUID per action |
| CC-23 | Apply again while a loan is pending, active or awaiting disbursal confirmation | Blocked (one live loan), with error `LOAN_IN_PROGRESS` → app shows the dashboard |
| CC-24 | Customer identity | **Server-generated `customer_id`** (`DN` + sequence). Mobile is an attribute. **No detail changes in v1** (mobile, email or anything else): a borrower who loses their number must contact support, which can only block the account. The navix `CUSTOMER_ID_COLLISION` path is removed. The navix borrower `PUT /profile/self` edit endpoint is disabled for borrowers |
| CC-25 | Same PAN on a second mobile | Blocked at the PAN step: "already registered", support path (navix check kept and verified by test) |
| CC-26 | Name/DOB mismatch across PAN, Aadhaar and bank holder | navix rules kept: DOB mismatch → fraud reject. Name match uses the penny-drop score threshold (config). Below threshold → REVIEW, not reject |
| CC-27 | Payout account differs from the salary account | Extra penny drop + name match. 3 failures → 12-hour lock (`PennyDropGuard`). Wireframe board EdgeBank |
| CC-28 | Staff user deactivated with assigned work | Their queue items return to the unassigned pool. Sessions revoked |
| CC-29 | Feature flag turned off with requests pending | Extensions already APPROVED/ACCEPTED/PAYMENT_PENDING remain actionable to completion. New requests blocked |
| CC-30 | Applications paused mid-journey | Users already past START continue. Only new STARTs see the gate form |

### C. Providers and callbacks (navix-verification, navix-loan)

| # | Case | Behaviour |
|---|---|---|
| CC-31 | Provider timeout or 5xx | Client timeout 30 s (bureau/BSA 120 s, as navix). Free calls retried once. **Billed calls (bureau, e-sign) never auto-retried**: they are deduplicated by `provider_api_execution` with a request hash. User sees "taking longer", with retry and support |
| CC-32 | E-sign webhook arrives but the app never returns from the custom tab (killed, closed) | On resume the app calls `GET /esign/status`. The webhook already moved the state. Journey resumes at the next step |
| CC-33 | DigiLocker consent declined, Aadhaar not linked, or a different person's Aadhaar | Declined → retry (3, server cap). Different person (name/DOB mismatch) → fraud review. After the cap → manual upload fallback (EdgeKyc) |
| CC-34 | Redirect URL tampering | Allowlist only (Phase 8) |
| CC-35 | Signzy/Digitap account or IP not allow-listed in the new stack | Startup health probe per provider in staging. Go-live checklist item |

### D. Auth, session, devices

| # | Case | Behaviour |
|---|---|---|
| CC-36 | OTP abuse / SMS pumping | Per-mobile limits (navix) **plus** per-IP and per-device limits in `attempt_counter`. Integrity token required on OTP request from the app |
| CC-37 | SMS autofill | Use the SMS User Consent API (no app hash needed, so the DLT templates don't change). Manual entry always works |
| CC-38 | Login on a second phone | Allowed. Each device has its own refresh token. "Log out of all devices" in Settings revokes all. New-device login sends an SMS alert |
| CC-39 | Refresh-token reuse (theft) | Whole token family revoked → re-login |
| CC-40 | Session expires mid-upload or mid-form | Refresh is transparent. If refresh fails, the form state is kept in memory, the user logs in again (E5) and returns to the same step |
| CC-41 | Biometric removed or changed, or no biometric | Falls back to OTP/password. Changed enrolment invalidates the biometric unlock key |
| CC-42 | Device without Google Play Services (Huawei etc.) | Integrity unavailable → OTP-only login with stricter limits. No push → SMS + in-app polling |
| CC-43 | Rooted or emulated device | Integrity verdict fails → login allowed for viewing and repaying only. New applications blocked with a support message |
| CC-44 | Device clock wrong | App never uses device time for logic. All dates come from the server |
| CC-45 | Account deletion (Play policy) | In-app request (Settings). Blocked while a loan is open. Otherwise login is disabled, marketing data erased, KYC/loan records kept for the regulatory retention period (config), then a purge job runs. Consent withdrawal recorded |

### E. App-specific (Flutter)

| # | Case | Behaviour |
|---|---|---|
| CC-46 | Process death or app killed mid-step | On restart: Splash → `GET /journey` → same step. Unsaved field input in multi-field forms is drafted locally (encrypted prefs) and cleared on submit |
| CC-47 | Back from the root screen | "Exit app?" confirm. Back during in-flight request is blocked until it ends |
| CC-48 | Camera or location permission denied, or "don't ask again" | Explainer → settings deep link (CameraDenied board). Location denied → B7 typed address. Mock-location flag sent to the server for review |
| CC-49 | Large or slow uploads | Images compressed client-side to ≤ 1.5 MB (server limit 5 MB). PDFs over 5 MB rejected before upload with a clear message. Presigned URL expiry → fetch a new one automatically. Upload retry is resumable per file |
| CC-50 | Password-protected PDF payslip | Password field (navix supports `filePassword`) |
| CC-51 | Content-type spoofing | Server validates magic bytes (PDF/JPEG/PNG/HEIC) after upload, before accepting |
| CC-52 | Forced update / maintenance | New `GET /api/app/config` → `{minVersion, latestVersion, maintenance, flags}`. Below minVersion → blocking "Update" screen. Maintenance → banner + read-only |
| CC-53 | Push/deep link to a loan that's closed, or to another user's item | Router resolves through the API. 404/forbidden → dashboard with a toast. Never shows stale data |
| CC-54 | Logged-out deep link | Stored, then applied after login |
| CC-55 | Android 13+ notification permission denied | Asked after the first loan, not at launch. Denied → in-app + SMS only |
| CC-56 | Android 14 partial photo access | Use the system photo picker. No broad storage permission |
| CC-57 | Sensitive screens | `FLAG_SECURE` on KFS, agreement, bank details and OTP screens (no screenshots or recents preview) |
| CC-58 | Font scale 200%, small 320dp screens, dark mode | Layouts tested in golden tests at 1.0/1.3/2.0 scale and 320/412 widths. App forces the light theme in v1 |
| CC-59 | Offline | Read screens show cached last response (in-memory + last-good JSON in app storage). Actions disabled with the Offline banner (E3) |
| CC-60 | Poor network on a long call | Per-request timeout + cancel on screen exit. Spinner text changes after 10 s ("still working") |

### F. Notifications and operations

| # | Case | Behaviour |
|---|---|---|
| CC-61 | FCM token rotated or app uninstalled | Token refreshed on change. FCM "unregistered" response deletes the token |
| CC-62 | SMS/WhatsApp/email delivery failure | navix delivery log reused. Critical events (due reminder, overdue, disbursal) go out on at least two channels |
| CC-63 | DND hours | Reminders between 09:00 and 21:00 IST only (config). OTPs are exempt |
| CC-64 | Complaint SLA counts working days | New `holiday_calendar` table (ADMIN-maintained). The SLA uses it. The 15-day resolution counts calendar days |
| CC-65 | Reports and dashboards using old constants | navix `ReportService`/`TeamQueries` hard-coded rate strings and the 25% limit are updated to read the snapshot. A test asserts no "1% per day accrued" or salary-limit wording remains |
| CC-66 | DEFAULTED / WRITTEN_OFF | Not set automatically in v1. ADMIN + FINANCE maker-checker can write off with a reason (`balance_adjustment`). Reporting buckets kept |

## Reused unchanged from navix

The onboarding steps and verification services:
- PAN, email OTP, penny drop, DigiLocker, bureau/KBA, salary slips, employment, references;
- Signzy e-sign + webhook;
- Digio mandate + debit scheduler (updated to the new status, kept OFF by flag);
- the manual repayment record/verify/reject flow;
- collections cases, settlements (`SettlementService` maker-checker);
- notification engine (templates rebranded);
- storage/presign;
- referral, DSA, leads, telecalling, blocklist, waitlist gate;
- staff console pages not listed in Phase 10.

## Verification (end to end, before calling it done)

1. `./mvnw verify -Pit` green, coverage gates met.
2. `docker compose up` with backend (mock providers) and web. Run Playwright: staff sanctions a ₹1,000 application → disburses with UTR → a second user confirms → the loan is ACTIVE with net ₹742 and due on day +14.
3. Flutter `integration_test` on an emulator against the same stack: first-time journey through closure; the credit level shows L2 / ₹2,000.
4. Clock-shifted IT: DUE_TODAY → OVERDUE_D1 → OVERDUE_REVIEW, penal accrues 2%/day capped at 30, a dispute pauses reminders.
5. Staging: officer offers an extension on a 3-days-late ₹1,000 loan → Head approves → borrower confirms with OTP and pays ₹318 in the app → Accountant verifies → due moves 19 Oct → 2 Nov, "Extended 1 of 3" on dashboard, addendum PDF stored, principal still ₹1,000; repeat to the cap. EMI (flag on) ₹7,000 → 3 instalments summing exactly.
6. KFS, sanction letter and agreement PDFs: every field present, lender printed, no "DhanBoost".
7. Prod profile boots only with real lender, grievance and DLT config; it fails with placeholders.
8. Staging ZAP + k6 results attached to the release.
