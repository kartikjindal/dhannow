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

**Status (2026-11-01): done.** `mvn verify` is green (1,871 unit) and `mvn verify -Pit` is green (75 integration, up from 69; V99 applied to Testcontainers Postgres). Migration:
- **V99:** `DISBURSAL_CONFIRMATION_PENDING` added to `loan_application_status_check`; new `payment.recorded_by` (the staff member who recorded a payment; null for the borrower and for payments validated upstream).

Code:
- **Status machine.** `DISBURSEMENT_PENDING → DISBURSAL_CONFIRMATION_PENDING → DISBURSED → ACTIVE`, plus `DISBURSAL_CONFIRMATION_PENDING → DISBURSEMENT_PENDING` for a return. `DISBURSEMENT_PENDING → DISBURSED` is gone.
- **UTR entry.** `disbursementDecision` (Disbursement Head) still requires the UTR. It now mints the loan `DISBURSEMENT_PENDING` (navix `status` DISBURSING) with the UTR on `loan.disbursal_txn_ref` and an indicative schedule, and parks the file. The action stays `VALIDATE_SUCCESS`, so the actor directory and decision history still name the Head as the disburser.
- **Confirm.** `POST /api/applications/{id}/disbursal/confirm` (ACCOUNTANT) runs `finalizeDisbursal`. `LoanService.confirmDisbursal` sets day 0 = today in IST (the confirmed date), recomputes the due date, `due_at` (23:59:59 IST, from the frozen product snapshot) and tenure, then calls `moveTo(ACTIVE)`. The ladder, referral and DSA hooks run then, and the application moves DISBURSED → ACTIVE. The borrower's `LOAN_DISBURSED` notification still fires on `ACTIVATE`.
- **Return.** `POST /api/applications/{id}/disbursal/return` (ACCOUNTANT, reason required) handles a wrong UTR or money not credited. The file goes back to DISBURSEMENT_PENDING, the unconfirmed loan row is deleted (`LoanService.discardPending`), and the event keeps the returned UTR. The Head re-enters the UTR and a new pending loan is minted.
- **`SeparationOfDutiesGuard`.** Moved from `navix-iam` to `navix-common` (`com.navix.common.security`) so loan and collections can call it. It is now static, and there is one rule per checker step: `disburser`, `disbursalConfirmer`, `paymentVerifier`, `emiApprover`, `extensionApprover`, `extensionPaymentVerifier`. The last three are ready for Phase 6. The guard ignores roles, so ADMIN is bound by it, while ADMIN still passes every role check.
- **Enforced today:**
  - sanctioner ≠ disburser (at UTR entry);
  - confirmer or returner ≠ sanctioner and ≠ disburser;
  - payment recorder ≠ verifier (`RepaymentService.verifyPayment`, recorder from `payment.recorded_by`);
  - collections payment raiser ≠ validator (`CollectionPaymentService.validate`).

  Violations return 422 `SOD_VIOLATION`.
- **Borrower view (additive).** `GET /api/applications/{id}` shows `DISBURSAL_CONFIRMATION_PENDING` with the `loanId`. `GET /api/loan/{id}` shows `lifecycleStatus` DISBURSEMENT_PENDING, `status` DISBURSING and the UTR. This is the app's "money sent, confirming" stage (Stage.disbursal, loan `Lifecycle.disbursementPending` + `disbursalUtr`). A repayment on an unconfirmed loan returns 422 `LOAN_NOT_ACTIVE`.
- **New status in existing lists.** Added to the staff "reached sanction" set, the mandate-setup set and the DSA lead mapping (IN_PROGRESS). The Accountant's queue is `GET /api/applications?status=DISBURSAL_CONFIRMATION_PENDING`.

Tests:
- `SeparationOfDutiesGuardTest` (moved; every Phase 4 and Phase 6 rule pair).
- `ApplicationFlowServiceTest`: UTR waits for the Accountant, the sanctioner cannot enter the UTR even as ADMIN, the confirmer differs from the sanctioner and the disburser, ACCOUNTANT-only, return, `cc23_applyAgainWhileAwaitingDisbursalConfirmationIsBlocked`.
- `LoanServiceTest`: `cc01_confirmationOnALaterDayMovesDayZeroToTheConfirmedDate` (UTR on 1 Nov, confirmed 3 Nov at 00:30 IST → due 17 Nov 23:59:59 IST), discard only while pending.
- `RepaymentServiceTest`: recorder ≠ verifier as ADMIN, borrower payments have no staff recorder, no payment while pending.
- `CollectionPaymentServiceTest`: raiser ≠ validator as ADMIN.
- `ApplicationFlowIntegrationTest`:
  - happy path UTR → borrower sees pending + UTR → Accountant queue → confirm → ACTIVE;
  - `sod_adminNoLongerWalksAnApplicationToActiveSolo` (sanctioner→disburser, sanctioner→confirmer, disburser→confirmer, disburser→return);
  - `sod_paymentRecorderCannotVerify`;
  - `sod_collectionsPaymentRecorderCannotValidate`;
  - `cc01_dueDateIsFixedFromTheConfirmedDateNotTheUtrEntry`;
  - the return path, `cc23_…` and the V99 check.

Deviations:
- **Path.** The backend endpoint is `/api/applications/{id}/disbursal/confirm`. The staff console reaches it as `/api/staff/applications/{id}/disbursal/confirm` through the existing BFF proxy, like every other staff application action.
- **Confirmed date.** It is the server's IST date at confirmation. The Accountant does not type a date, which is borrower-favourable when confirmation is late (CC-01).
- **Return path added.** Without it, a mistyped UTR would strand the file.
- **ADMIN `admin-record`.** It no longer verifies in the same call. The payment now waits for an Accountant, because recorder ≠ verifier binds ADMIN too.
- **No recorder on upstream-validated credits.** Collections and NACH credits (`LoanDirectory.creditCollectionPayment`) carry no recorder: their maker-checker runs upstream, and raiser ≠ validator is now enforced there.
- **CC-23 error codes.** CC-23 keeps the navix codes `ACTIVE_APPLICATION` / `ACTIVE_LOAN` (the web routes on them) instead of `LOAN_IN_PROGRESS`.
- **CC-01 partly done.** Schedule v1, the `SCHEDULE` document and the SMS/push of the final due date wait for Phase 5 (documents) and the notification work.
- **No Accountant alert.** The Accountant is not notified when a file enters the confirmation queue; the retired `DISBURSEMENT_PENDING_ACCOUNTANT` type is not revived.
- **Staff console.** It has no confirm/return buttons yet, and the segment "Awaiting disbursal confirmation" is not built (Phase 10b, R6). A pending loan reads as "active" in the customer segments, where DISBURSING was already counted.
- **EMI and extension SoD.** These are rule definitions only; the code they guard is Phase 6.
- **CC-21.** `@Version` is still not on `loan_application`.

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

**Status (2026-11-01): done.** `mvn verify` is green (1,889 unit) and `mvn verify -Pit` is green (78 integration, up from 75; V100 applied to Testcontainers Postgres). Migration:
- **V100:** `loan_document` gains `application_id`, `version`, `pricing_version_id`, `amount_paise`, `s3_key`, `sha256`, `created_at`, `accepted_at`, `accepted_ip` and `acceptance_evidence jsonb`; `loan_id` becomes nullable with a check that a row belongs to an application or a loan, and `(application_id, type, version)` is unique. Also `loan_application.offer_valid_until`, `product_config.offer_validity_days` (default 3, frozen by the existing trigger) and an append-only `loan_document_access` table.

Code:
- **One set of facts.** `DocumentFacts` is built by `LoanDocumentService.facts` from the frozen pricing and product snapshots through `LoanMath` and `AprCalculator`; the renderers only format. `KfsPdfRenderer` (KFS + the repayment schedule), `SanctionLetterPdfRenderer` (rewritten on the DhanNow template) and `LoanAgreementPdfRenderer` share their layout and wording through `PdfKit`. Inter is embedded so ₹ renders. The "no auto-debit mandate" line is gone.
- **APR.** `AprCalculator` (interface) with `Method.IRR` (default: (principal / net)^(365/14) − 1, the IRR of −net at day 0 and +principal at day 14, compounded) and `Method.SIMPLE`, chosen by `navix.apr.method`. ₹1,000 → ₹742 / ₹1,000 / 14 days pins **239,096.78%** (IRR) and 906.53% (simple); every ladder amount has the same APR.
- **Config.** `navix.brand.*`, `navix.grievance.*` (Dharmendra Rajput / 9211331170 / grievance@dhannow.com) and `navix.lender.*` are `@ConfigurationProperties` records in `navix-common` (`com.navix.common.brand`), with the defaults in `application.yml` only. The lender defaults are `[placeholder]`s; `ProductionConfigGuard` refuses to start with `NAVIX_ENV=prod` while any lender value is blank or a placeholder.
- **KFS flow.** `POST /api/applications/{id}/offer/kfs` issues or reuses the KFS and returns a 5-minute link; `POST …/offer/kfs/ack` records `accepted_at`, the IP (last `X-Forwarded-For` hop) and evidence. New journey step `OFFER_KFS` (route `/loan/kfs`) after SUMMARY; the journey holds an unsigned borrower there until the KFS is acknowledged. `esign/init` and the drawn-signature fallback refuse with 422 `KFS_NOT_ACKNOWLEDGED` (or `OFFER_EXPIRED`). An acknowledgement binds to the offer it was shown for: same amount, same pricing version, offer still open.
- **CC-02.** `PricingService.freeze` (sanction and re-apply) opens the validity window. A lapsed offer is re-quoted on the ACTIVE pricing the next time the KFS is opened, which issues a new KFS version to acknowledge; the old one can never be signed.
- **Signing.** `POST …/offer/sanction-letter` issues the `SANCTION` document and stores the pack the borrower signs (sanction letter + agreement) as the navix `SANCTION_LETTER` application document, so the Signzy flow runs unchanged. On PASS (status poll, webhook or drawn signature) the signed copy is recorded once as `AGREEMENT` (S3 key, SHA-256 of the stored bytes, `accepted_at`, method and provider reference) and the sanction letter is stamped accepted.
- **CC-01 schedule.** When the Accountant's confirmation commits (`ACTIVATE`), a `SCHEDULE` document with the confirmed due date is issued on the loan, in its own transaction; a storage failure is logged and never undoes the confirmation.
- **Access.** `GET /api/loans/{id}/documents` (list, the offer's documents plus the loan's) and `GET /api/loans/{id}/documents/{docId}/url` (presigned GET, 5 minutes; `DocumentStoragePort.presignDownload(key, ttl)` added). A borrower reading another customer's loan gets 422 `FORBIDDEN_OWNER`, DSA gets `FORBIDDEN_ROLE`, and each staff link is written to `loan_document_access`.
- The e-sign document title on the provider page uses `navix.brand.name`.

Tests:
- `AprCalculatorTest` (APR pinned, every ladder amount, simple method).
- `LoanDocumentServiceTest`: money parity of the facts with `LoanMath` for all seven amounts; golden text (OpenPDF `PdfTextExtractor`) of the KFS, sanction letter, agreement pack, signed pack and schedule with every required field and the ₹ figures; no "DhanBoost", "NBFC", auto-debit, salary or pay-early wording in any output; KFS acknowledgement gate; acknowledgement bound to the amount; `cc02_aLapsedOfferIsRequotedAndItsOldKfsCanNeverBeSigned`; the signed agreement recorded once; `cc01_theScheduleIsIssuedOnceWithTheConfirmedDueDate`; schedule failure does not undo confirmation; cross-customer `FORBIDDEN_OWNER`; 5-minute link and staff audit.
- `OfferServiceTest` (e-sign refused without acknowledgement, drawn signature records the agreement, ack advances the journey), `JourneyServiceTest` (KFS step and hold), `ProductionConfigGuardTest` (placeholder and blank lender fail prod, dev boots).
- `ApplicationFlowIntegrationTest`: `documents_kfsAckGatesESignAndEveryDocumentIsOnTheLoan` (KFS → ack → e-sign → AGREEMENT → confirm → SCHEDULE → list for the owner, 5-minute link, `FORBIDDEN_OWNER` for another borrower, staff read audited), `cc02_aLapsedOfferIsReQuotedAndItsOldKfsCanNeverBeSigned`, and the V100 checks.

JaCoCo's 80% gate now also covers `LoanDocumentService`, `AprCalculator` and the document renderers.

Deviations:
- **Documents on the offer.** The KFS, sanction letter and agreement are issued before any loan exists, so `loan_document` rows carry `application_id`; the loan's list joins them through the application. `amount_paise` was added to tell which offer a KFS describes.
- **One e-sign for two documents.** The borrower signs one pack (sanction letter + agreement); its acceptance stamps both. The navix `SANCTION_LETTER` / `SIGNED_AGREEMENT` application documents are still written, because the e-sign flow, the staff console and the signed-letter email read them.
- **PDFBox not added.** OpenPDF (already a dependency) extracts the text, ₹ included, so no new test dependency.
- **Indian digit grouping.** Java's `NumberFormat` groups in threes even for `en-IN`, so the APR reads 239,096.78%. Ladder amounts (≤ ₹10,000) are unaffected.
- **Prod switch.** The lender check runs on `NAVIX_ENV=prod`, the switch the existing guard uses, rather than a Spring `prod` profile.
- **Step-up OTP (`X-Step-Up`).** Not built: it needs the Phase 8 auth work. Documents are owner-checked and links live 5 minutes.
- **Copy.** The grievance line uses the RBI 30-day escalation (cms.rbi.org.in); the 15-day SLA waits for the Phase 7 complaint ticket. Cooling-off reads "Not applicable to this loan" (none in v1). Lender wording is a draft pending Legal.
- **CC-01 partly done.** The `SCHEDULE` document is issued; the SMS/push of the final due date waits for the notification work. Other "DhanBoost" strings in notification templates and internal provider labels are not part of the documents and are left for the brand sweep.
- **Navix (ACCRUED) offers.** Pre-V92 offers render on the navix terms with the sanctioned tenure; they are not shown to DhanNow borrowers.

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

**EMI** (flag `emi-conversion`; V104 `emi_conversion`):
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

**Status (2026-11-01): 6a done.** Phase 6 runs in three parts: 6a (payments, closure, schedules, the breakdown money model), 6b (loan extension), 6c (EMI and the report purpose guard). `mvn verify` is green (1,904 unit) and `mvn verify -Pit` is green (82 integration, up from 78; V101–V102 applied to Testcontainers Postgres). Migrations:
- **V101:** `payment_allocation(payment_id, loan_id, component PENAL|INTEREST|PRINCIPAL|EXCESS, amount_paise)`, unique per payment and component; `idempotency_key(key, scope, request_hash, result_id, created_at)`; `refund_case(loan_id, customer_id, payment_id | mandate_debit_id, amount_paise, reason OVERPAYMENT|NACH_AFTER_CLOSE, status OPEN|REFUNDED|CANCELLED)`, one per source; `payment.txn_ref` normalised (no spaces, upper case) with a unique index across all non-rejected payments (CC-07).
- **V102:** `repayment_schedule` and `schedule_instalment` as listed above, one ACTIVE schedule per loan. Triggers refuse any edit of a schedule's terms or an instalment's dates and money, and any delete; only `status` (→ SUPERSEDED) and `paid_paise`/`status` on an instalment may change.

Code:
- **`PaymentAllocator`.** On verification, each payment is split over what was owed on its bank date (`paid_on`): late charge to that date, interest (0 on DhanNow terms), principal, less what earlier payments already covered, in the order of `navix.payments.waterfall` (`PENAL,INTEREST,PRINCIPAL`; INTEREST is listed only for loans on the navix terms). The rest is EXCESS and opens an OVERPAYMENT refund case (CC-08); money verified onto an already closed loan is all excess. Principal paid is spread over the active schedule's instalments.
- **Idempotency.** `IdempotencyService.execute(scope, key, request, action)` in `navix-common` inserts the key in the caller's transaction, so the action and its key commit together; a concurrent duplicate waits on the key and gets the first result. Same key with a different request → 422 `IDEMPOTENCY_KEY_REUSED`. `POST /api/loan/{id}/repayments` requires `Idempotency-Key` from a BORROWER (422 `IDEMPOTENCY_KEY_REQUIRED`, CC-22); `admin-record` honours it when sent. 6b/6c call the same helper for extension and EMI activation.
- **UTRs (CC-07).** A UTR already on file is 422 `DUPLICATE_UTR` on the borrower/ADMIN path, on any loan. The upstream-validated paths (collections, NACH, gateway) still get the existing payment back when the UTR is already on the same loan, so one transfer is never counted twice; on another loan they are refused too. A rejected payment frees its UTR for a resubmission.
- **Gateway.** `PaymentGatewayPort` (no implementation) and `POST /api/webhooks/payments/{provider}`: 422 `FEATURE_DISABLED` while `payment-gateway` is OFF, `UNKNOWN_PROVIDER` without an adapter; with both, a callback is recorded once per provider event id (key `provider:eventId`) as a payment awaiting the Accountant.
- **Closure.** Every closing path takes the application's `REPAID` transition; after it commits, `LoanDocumentService.onLoanClosed` issues a `CLOSURE_RECEIPT` once (payments, the penal/principal split, any refund; "Closed by an approved settlement" when principal was not paid in full), in its own transaction like the schedule. The borrower notification is the existing navix `LOAN_CLOSED` on that transition. The ladder hook is unchanged.
- **Refunds.** `RefundCaseService` writes the register; `GET /api/refund-cases[?status=OPEN]` (ACCOUNTANT, ADMIN). The navix NACH register (`mandate_debit.overpayment`, `GET /collections/debits/overpayments`) is kept, and a debit landing on a settled loan now also opens a `NACH_AFTER_CLOSE` case (the CC-11 "both clear → refund" half).
- **Schedule v1.** `LoanService.confirmDisbursal` writes the v1 BULLET schedule in the confirmation transaction: one instalment of the principal on the confirmed due date and `due_at` (fee, GST and interest were deducted at disbursal; on navix terms the scheduled interest is on the instalment).
- **Money model (item 5, Phase 10b R4).** `OutstandingBreakdown` and `OutstandingView` (`/api/loan/{id}/outstanding`, customer 360) gain `interestPrepaidPaise` (the frozen `loan.upfront_interest_paise`), shown and never owed. Owed = principal + late charge (penal rate × principal per day past due, from the frozen snapshot, stopping at the cap) − verified paid.
- `verifyPayment` refuses a REJECTED payment (422 `PAYMENT_REJECTED`) instead of reviving it (the bug noted in item 5).

Tests:
- `PaymentAllocatorTest` (`cc09_…` penal before principal, `cc08_…` excess, closed loan, configured order).
- `RepaymentServiceTest`: `cc07_aUtrAlreadyOnFileIsRefused`, upstream same-loan UTR returns the payment, UTR normalising, `cc22_aBorrowerSubmitNeedsAnIdempotencyKeyAndGoesThroughTheGuard`, rejected payment cannot be verified, `cc19_breakdownExcludesPrepaidInterestAndCapsTheLateChargeAtThirtyDays`, `cc09_verifyAllocatesAgainstTheLateChargeToThePaymentDate`, `cc08_aPaymentVerifiedAfterClosureIsAllExcess`.
- `LoanDocumentServiceTest`: closure receipt golden text and wording, `cc08_theClosureReceiptStatesTheRefund`, issued once on `REPAID`.
- `DebitServiceTest`: NACH-after-close opens a refund case, `cc11_bothClearOpensARefundCaseOnlyForTheDebitNoLongerOwed`.
- `ApplicationFlowIntegrationTest`: `payments_recordVerifyAllocateCloseReceiptAndLadderUp` (v1 BULLET schedule and its triggers, key required, replay returns the same payment, reused key refused, `DUPLICATE_UTR`, part → PARTIAL → full → CLOSED, allocation rows, instalment PAID, one `CLOSURE_RECEIPT`, level 2); `cc08_overpaymentOnALateLoanOpensARefundCaseAndTheLateChargeIsCapped` (40 days late owes ₹1,600, prepaid ₹140 shown, ₹1,700 paid → PENAL 600 / PRINCIPAL 1,000 / EXCESS 100, OPEN refund case listed for the Accountant, another customer's reuse of the UTR refused, level unchanged); `paymentWebhookIsRefusedWhileTheFlagIsOff`; `paymentWebhookReplayIsIdempotent` (flag ON, test adapter).

Deviations:
- **`idempotency_key` columns.** `request_hash` + `result_id` instead of `response_hash`: a replay returns the stored result's id (the caller re-reads it), and the hash binds the key to the actor, target and body, so one borrower's key can never return another's payment.
- **Lifetime 30-day cap.** The cap counts days past the current due date. Counting penal days across extension cycles needs `loan_due_history` (6b, EXT-26).
- **Gateway payments wait for the Accountant.** The stub records them PENDING_VERIFICATION; auto-verification is left to the real adapter's phase.
- **Same-loan UTR on the upstream paths.** Kept as a return of the existing payment (navix behaviour that NACH reconcile and collections rely on), not a 422.
- **Not built here.** `loan.closure_type` (CC-18), `POST /payments/{id}/reverse` (CC-10), the `balance_adjustment` reversal of CC-05, and refund case actions (mark refunded) and a staff screen (Phase 10b "Refunds owed"). The `LOAN_CLOSED` email still says "DhanBoost" (brand sweep, as noted in Phase 5). The app still talks to the demo server; when it moves to the real API it must send one `Idempotency-Key` per repayment submit.
- **For 6b/6c.** `payment` has no `purpose` yet; the sums in `RepaymentService`, the allocator's prior-allocation sums and `latestVerifiedPaidOn` will need the `purpose='REPAYMENT'` filter, and `findFirstByTxnRefAndStatusNot` the purpose-aware dedupe (`UTR_USED_FOR_OTHER_PURPOSE`). Extension money gets its own allocation components (`EXT_FEE`/`EXT_GST`/`EXT_INTEREST`); widen the V101 check. A new schedule version supersedes v1 (insert the new ACTIVE row after setting the old one SUPERSEDED; the partial unique index allows one ACTIVE). Schedule instalment `status` has no OVERDUE yet (CC-17, 6c).

**Status (2026-11-01): 6b done.** `mvn verify` is green (1,942 unit, up from 1,904) and `mvn verify -Pit` is green (86 integration, up from 82; V103 applied to Testcontainers Postgres). Migration:
- **V103 `loan_extension`:** the table (status, `version`, fee/GST/interest frozen at offer, `pay_by`, `expires_at` = 23:59:59 IST on pay-by, old/new due date, call note + recording id, override reason, proposer/approver/rejecter/canceller, `accepted_at` + `consent_version`, `declined_reason`, `seen_by_borrower_at`, `payment_id`, resend counter), `unique (loan_id, seq)` and the partial unique index `uq_loan_extension_open` (one OFFERED/APPROVED/ACCEPTED/PAYMENT_PENDING per loan). `product_config.extension_max` (3, frozen by the trigger). `collection_payment` kind `EXTENSION`, `extension_id`, `version`. `payment.purpose` (`REPAYMENT`/`EXTENSION`) + `extension_id` with a check, and one live payment per extension. `payment_allocation` widened for `EXT_FEE`/`EXT_GST`/`EXT_INTEREST`. `loan.extension_count`, `original_due_date`, `penal_days_used`, `penal_carried_paise`. `loan_due_history`. `credit_level_history` reason `EXTENDED_NO_CHANGE`. A minimal `balance_adjustment`.

Code:
- **Loan side (`LoanExtensionLedger`, navix-loan).** The quote (`quote(loan, math, paidOn, repaymentPenalPaid)`, pure): fee + GST + one tenure of interest from the frozen snapshot, plus the late charge of the current cycle to `paid_on` within the lifetime cap, plus earlier-cycle late charge not yet paid, less what repayments paid of it. ₹1,000 3 days late = ₹318. Eligibility facts with reason codes (`NOT_DUE_YET`, `LOAN_CLOSED`, `RESTRUCTURED`, `ON_HOLD`, `REPAYMENT_PENDING`, `DEBIT_IN_FLIGHT`, `EXTENSION_CAP_REACHED`; the state is the clock's when the lifecycle job has not run yet). `activate` is the only code that moves a due date: under the NACH loan advisory lock, with the extension PAYMENT_PENDING and the payment equal to the quote for its `paid_on`, it writes the allocation rows, closes the due cycle and opens the next in `loan_due_history`, sets due += tenure and `due_at`, `extension_count`++, `penal_days_used` += the days charged, lifecycle → ACTIVE (which also clears IN_COLLECTIONS), a new EXTENSION schedule version (v1 superseded first), a `balance_adjustment` row, the extension ACTIVE, and `ExtensionActivatedEvent`. A debit in flight blocks activation. SoD `extensionPaymentVerifier` (and `paymentVerifier`) on every path.
- **Money model.** `breakdown` counts late days through `LoanMath.chargeablePenalDays` (lifetime cap, EXT-26) and adds `penal_carried_paise`; the verified sums (`PaymentRepository`), `latestVerifiedPaidOn`, `repayment_status`, the allocator's prior allocations and the closure receipt read REPAYMENT money only. Extension money never reduces principal.
- **`RepaymentService`.** `submitPayment(... purpose, extensionId)`; an EXTENSION payment must be the exact quote (422 `EXTENSION_AMOUNT_MISMATCH`), is never partial, and moves the extension to PAYMENT_PENDING. A UTR on file for the other purpose is 422 `UTR_USED_FOR_OTHER_PURPOSE`. Verify activates (or, if the extension was cancelled because the loan closed, refunds all of it as EXCESS, CC-69); reject returns the extension to ACCEPTED (EXT-19). Closing a loan cancels any open extension (EXT-06).
- **Collections side (`LoanExtensionService`, navix-collections).** Offer (COLLECTION_EXECUTIVE on an assigned case or COLLECTION_HEAD; flag `loan-extension`; refused while a settlement is open; pay-by from today to before the new due date), approve (COLLECTION_HEAD, SoD `extensionApprover`, refused after pay-by, eligibility re-checked), reject (picklist + note), cancel (not while a payment is pending), resend (3 a day). Every transition takes the loan lock, goes through `ExtensionStatus` and `@Version`; an illegal or stale move is 409 `STALE_STATE` with the current state (`ConflictException`, new 409 handler, which also maps optimistic-lock failures). `SettlementService.propose` refuses while an extension is open (`EXTENSION_OPEN`). `CollectionPaymentService`: kind `EXTENSION` needs the case's ACCEPTED extension and the exact quote; validation takes the loan lock and calls `LoanDirectory.applyExtension` instead of `creditCollectionPayment`; a rejection returns the extension to ACCEPTED. `ExtensionDirectory` (navix-common port, `ExtensionDirectoryAdapter`) lets the loan module move extensions without a bean cycle.
- **Expiry.** `ExtensionExpirySweep` (navix-app, 00:05 IST, `scheduler_lock`, idempotent) expires OFFERED/APPROVED/ACCEPTED past pay-by, never PAYMENT_PENDING. Reads treat a lapsed offer as EXPIRED before the sweep.
- **Notifications.** Category `EXTENSION`; types `EXTENSION_TO_APPROVE` (Heads), `EXTENSION_OFFERED` (borrower IN_APP + EMAIL, on the Head's approval), `EXTENSION_ACTIVATED` (borrower IN_APP + EMAIL + SMS), `EXTENSION_EXPIRED` / `EXTENSION_DECLINED` (assigned officer, else the proposer); events `ExtensionOffered/Approved/Activated/Expired/DeclinedEvent`. The `EXTENSION_ADDENDUM` PDF (`KfsPdfRenderer.renderExtensionAddendum`) is issued once per activation after commit, like the 6a closure receipt.
- **Worklist.** `findCollectible` and the worklist SQL keep every loan with `extension_count > 0`; rows carry `extState` (latest extension status, sortable and faceted) and `extensionCount`.
- Ladder: a closed loan with any activated extension records `EXTENDED_NO_CHANGE`.

API (staff, under `/api/collections`): `GET|POST /cases/{caseId}/extensions`, `POST /loans/{loanId}/extensions` (opens the case first), `POST /extensions/{id}/approve|reject|cancel|resend`; `POST /cases/{caseId}/payments` takes `kind=EXTENSION` + `extensionId`. Borrower: `GET /api/loan/{id}` gains `originalDueDate`, `extensionCount`, `pendingExtension{id, status, version, seq, cap, quoteForToday{feePaise, gstPaise, interestPaise, lateChargePaise, lateDays, paidOn, totalPaise}, payBy, expiresAt, newDueDate, seen}` and `extensions[]{id, seq, status, oldDueDate, newDueDate, activatedAt, paymentId}`; `POST /api/loans/{id}/extensions/{extId}/seen|otp|accept {otp, consentVersion, version}|decline {reason NOT_NOW|DID_NOT_ASK, version}`; payment is `POST /api/loan/{id}/repayments` with `purpose=EXTENSION, extensionId`. `PaymentView` gains `purpose` and `extensionId`.

Tests:
- `LoanExtensionLedgerTest`: `cc15_quoteForOneThousandPaidThreeDaysLateIs318`, `cc15_lateChargeAlreadyPaidByARepaymentIsNotChargedAgain`, `cc16_newDueDateIsThePreviousDuePlusFourteen`, `ext26_theLateChargeCapIsALifetimeCapAcrossCycles`, `ext02_anIneligibleLoanGetsASpecificReasonCode`, `ext16_aPaymentThatIsNotTheQuoteNeverExtends`, `ext09_aPaymentForAnExtensionNotWaitingForOneIs409`, `cc69_verifyingAPaymentForACancelledExtensionDoesNotActivate`.
- `RepaymentServiceTest`: `ext15_aUtrAlreadyUsedForARepaymentCannotPayAnExtension`, extension payment recorded against its extension, `ext19_…`, verify activates and leaves principal alone, `cc69_…` refund, `ext26_theBreakdownCountsLateDaysUsedInEarlierCycles`. `CreditLevelServiceTest.ext28_anExtendedLoanClosesWithoutALevelChange`.
- `LoanExtensionServiceTest`: the state machine, offer, `ext01_…` (409 and the index race), `ext02_…`, `ext03_…`, `ext04_theHeadCannotApproveTheirOwnOffer`, `ext05_…`, `ext25_theCapNeedsAnAdminOverrideWithAReason`, `cc29_…`, `ext11_…`, `ext12_acceptIsIdempotent`, `ext09_aStaleVersionIs409`, cross-borrower `FORBIDDEN_OWNER`, `cc71_didNotAskDeclinesAndTellsTheOfficer`, `cc67_anAcceptedOfferNeverPaidExpires`, `ext21_theSweepSkipsAPendingPayment`, `ext07_resendIsLimitedToThreeADay`, no cancel while paid. `CollectionExtensionPaymentTest`: `ext16_…` (mismatch refused), validation applies the extension (never `creditCollectionPayment`), approver cannot validate (SOD), `ext19_…`. `SettlementServiceTest.proposeIsRefusedWhileAnExtensionIsOpen`.
- `ApplicationFlowIntegrationTest`: `extension_offerApproveAcceptPayVerifyMovesTheDueDateAndClosesWithNoLevelUp` (offer → 409 + DB index → panel ₹318 → own-offer SOD → approve → borrower sees it → seen → OTP (wrong code refused) → accept, replay idempotent → short payment refused → ₹318 recorded, due unchanged → Accountant verifies → due + 14, `Extended 1 of 3`, allocations EXT_FEE 100 / EXT_GST 18 / EXT_INTEREST 140 / PENAL 60 and no PRINCIPAL, outstanding ₹1,000, schedule v1 SUPERSEDED + v2 EXTENSION, two due-history rows, one `balance_adjustment`, one addendum, still on the worklist with `extState=ACTIVE` → ₹1,000 repaid → CLOSED, level 1, `EXTENDED_NO_CHANGE`); `ext20_twoAccountantsValidatingAtOnceMoveTheDueDateOnce` (two threads, one 200, one extension payment, then the UTR is refused for a repayment); `ext25_aFourthExtensionNeedsAnAdminOverride`; `cc67_anAcceptedOfferThatIsNeverPaidExpires` (the sweep).

Deviations:
- **Migration numbers.** The extension is V103; 6c takes V104 for `emi_conversion`, so Phase 7's V104–V106 become V105–V107. `balance_adjustment` is created (minimal) in V103; Phase 7 extends it (reason-code lookup, reconciliation) instead of creating it.
- **Loan columns beyond the plan.** `penal_days_used` and `penal_carried_paise` carry the lifetime cap and earlier-cycle late charge paid by repayments, so the batched outstanding stays one query per page. `loan_due_history` is written from the first extension on (seq 1 is the original cycle); an unextended loan's only cycle is the loan row itself.
- **No outbox.** There is no outbox table in the codebase. Notifications use the existing after-commit async listeners and the addendum is issued after commit in its own transaction (as 6a did for the closure receipt); a failure is logged and never undoes the activation. Retries with backoff and the alert after 3 failures (EXT-22) wait for an outbox (Phase 9).
- **Events.** `ExtensionAcceptedEvent` and `ExtensionPaymentRecordedEvent` are not created: no notification type consumes them (the Accountant hears the existing `REPAYMENT_RECORDED` / `COLLECTION_PAYMENT_TO_VALIDATE`). The borrower gets `EXTENSION_OFFERED` when the Head approves (an unapproved offer is never shown). PUSH is Phase 8; `EXTENSION_ACTIVATED` SMS delivers once its DLT template is approved, and `EXTENSION_OFFERED` SMS waits for `DN_EXT_OFFER`.
- **Declining.** Allowed from APPROVED as well as ACCEPTED (the borrower can say "Not now" on C12 before confirming). OFFERED also expires at pay-by, which frees the loan's one open slot for a fresh offer (EXT-05).
- **Accept idempotency** is by state (once accepted, a retry returns ACCEPTED without a code); `IdempotencyService` stores bigint results and still guards the payment submit (`Idempotency-Key`), which is what the app retries.
- **Exact amount only.** An extension payment that is not the quote for its `paid_on` is refused when recorded (borrower, ADMIN and collections paths), so EXT-16's short/long cases never reach the Accountant as extension payments. Not built: the Accountant's "Treat as extension payment" on a repayment (EXT-14) and "Re-open for this payment" (EXT-18); the officer re-offers instead. Both are Phase 10b backend + G4 work.
- **CC-70.** One live payment per extension (`uq_payment_live_extension`) plus the state machine refuses a second payment at record time; money already sent is recorded as a part payment or refunded by the Accountant.
- **CC-71 / EXT-27.** "I didn't ask" is stored as `declined_reason=DID_NOT_ASK` and tells the officer; opening the complaint ticket waits for Phase 7, as does cancelling an open offer when a dispute is raised (no dispute endpoint yet; a hold already blocks offers and approvals).
- **Override permission.** The server checks role ADMIN + a reason; `collections:extension-override` is the web RBAC name (Phase 10, `rbac.ts`).
- **NACH (flag off).** `shouldPresent`/`DebitService.prepare` still count attempts over the loan's life, not per cycle; activation is refused while a debit is in flight. Per-cycle counting is left until eNACH is switched on.
- **Upcoming watchlist.** Its case filter is unchanged: extended loans are on the worklist whatever their due date, so they are not hidden.
- **Not here (6c).** Item 10 (report queries' `purpose` handling and the grep guard test) and EMI.

For 6c / Phase 10 / Phase 11:
- 6c: every report query reading verified `payment` must handle `purpose` (extension income has its own series; `TransactionLedgerQuery` row type `EXTENSION`). `payment_allocation` PENAL rows from extension payments belong to the extension; the closure receipt already filters them.
- Phase 10 (G1–G5): worklist rows have `extState`/`extensionCount`; `GET /cases/{id}/extensions` returns `{eligible, reasonCode, cap, used, quoteForToday, dueDate, originalDueDate, newDueDate, current, history}`; `RecordPaymentCard` sends `kind=EXTENSION` + `extensionId` with the amount locked to `quoteForToday.totalPaise`; the Accountant queues can badge `purpose=EXTENSION` (repayments) or `kind=EXTENSION` (collections); 409 bodies carry the current state in `error.details[0]`.
- Phase 11 (C12–C12d): render from `pendingExtension.status` (APPROVED → confirm with OTP; ACCEPTED → pay; PAYMENT_PENDING → waiting; null + last `extensions[]` entry for ACTIVE/EXPIRED/DECLINED/CANCELLED); send `version` on accept/decline and one `Idempotency-Key` per payment submit; `quoteForToday` is for paying today (the late charge follows the bank date).

**Status (2026-11-01): 6c done; Phase 6 done.** `mvn verify` is green (1,957 unit, up from 1,942) and `mvn verify -Pit` is green (91 integration, up from 86; V104 applied to Testcontainers Postgres). Migration:
- **V104 `emi_conversion`:** the table (status REQUESTED/APPROVED/ACTIVE/REJECTED/CANCELLED/DECLINED/EXPIRED, `version`, `instalment_count` + `gap_days` frozen at request, `accept_by`/`expires_at`, requester/approver/rejecter/canceller, `accepted_at` + `consent_version`, and at activation `conversion_date`, `principal_paise`, `charges_paise`, `interest_mode`, `schedule_version`), one open (REQUESTED/APPROVED) per loan (`uq_emi_conversion_open`). `product_config.emi_gap_days` (10) and `emi_three_from_paise` (₹7,000), frozen by the trigger. `schedule_instalment.penal_paise` (late charge converted into an EMI, frozen) and status `OVERDUE`. `loan.closure_type` (REPAID/SETTLED, backfilled) and `loan.restructured_at`. `payment` status `REVERSED` + `reversed_by/at`, `reversal_reason`. `refund_case.refund_utr`, `refunded_on`, `decided_by`, `cancel_reason`. `credit_level_history` reason `CLOSURE_REVERSED` + `reversed_at` (the one-closure-per-loan index skips reversed rows).

Code:
- **EMI (`EmiConversionService`, navix-loan; flag `emi-conversion`, OFF).** COLLECTION_EXECUTIVE / COLLECTION_HEAD request on an overdue loan (reason codes `NOT_OVERDUE`, `LOAN_CLOSED`, `RESTRUCTURED`, `ON_HOLD`, `REPAYMENT_PENDING`, `DEBIT_IN_FLIGHT`, `EXTENSION_OPEN`, `SETTLEMENT_OPEN`, `FEATURE_DISABLED`; a second open request is 409 `EMI_ALREADY_OPEN`); accept-by from today to `offer_validity_days` ahead. Only an ADMIN approves or rejects (anyone else 422 `FORBIDDEN_ROLE`), never the requester (`SeparationOfDutiesGuard.emiApprover`); approval puts the loan in RESTRUCTURE_PENDING. The borrower accepts with an OTP (purpose `EMI_CONVERSION`, mobile resolved server-side), which activates it once through `IdempotencyService` (key `emi:<id>`) under the loan advisory lock: what is owed that day (principal still open + late charge to date) is split into two instalments, or three from `emi_three_from_paise`, every `emi_gap_days` days from the conversion date, remainder paise on the last (principal and charges each); the loan goes RESTRUCTURED with its late charge frozen (`penal_carried_paise`, `restructured_at`: the breakdown charges no late days after it); the active schedule is superseded by an EMI version; a `balance_adjustment` row (`EMI`) records before/after and the consent. Decline (`NOT_NOW`/`DID_NOT_ASK`), cancel (collections or ADMIN) and expiry (the 00:05 sweep, which now also expires EMI offers) put the loan back to the overdue state its dates give. Closing the loan cancels an open offer. An extension is refused while a conversion is open (`EMI_OPEN`) and a conversion while an extension is open. Every move takes the loan lock, `EmiStatus` and `@Version` (409 `STALE_STATE`). EMI interest = `navix.emi.interest-mode` (`NONE`, the only mode defined).
- **Schedules.** Payments fill EMI instalments with principal and late charge (an EMI instalment carries both); CC-17: the sweep marks open instalments past `due_at` OVERDUE (`RepaymentScheduleService.markOverdue`), and a payment still fills them.
- **Report purpose guard (item 10, EXT-30).** Every query reading `payment` says which purpose: recovery figures read `purpose='REPAYMENT'` (`CollectionQueries`, `MetricSql`, `BusinessQueries`, `TeamQueries` sanctioner book, `ReportService` summary, monthly, customers, repeat customers, closures, on-time, officer collections, income, repayments report), extension income is its own series (summary row "Extension charges verified", a monthly column, an income column, transactions-report type `Extension`), `TransactionLedgerQuery` rows are typed `EXTENSION` (and drop REVERSED), and the accountant workload / pending queue count both purposes (and say so). `PaymentPurposeGuardTest.ext30_everyQueryReadingPaymentsSaysWhichPurpose` scans every `src/main/java` file for `from payment` / `join payment` (SQL and JPQL) and fails when a statement has fewer `purpose`/`extension_id` mentions than payment reads. The navix reborrow late check ignores extension charges.
- **Closure type (CC-18).** Set on closing: SETTLED with an approved settlement, else REPAID.
- **CC-05.** A loan closed by a payment whose `paid_on` is on or before the due date, verified while OVERDUE_*, gets a `LATE_REVERSED` `balance_adjustment` (the waterfall already charged no late fee, and the ladder judges it on time).
- **Reversal (CC-10).** `POST /api/loan/{loanId}/repayments/{paymentId}/reverse {reason}`: ACCOUNTANT or ADMIN, never the payment's recorder or verifier. The payment becomes REVERSED; its allocation rows are removed (kept in the audit row), its instalment payment undone, its open refund case cancelled (a refund already paid is 422 `REFUND_ALREADY_PAID`). A closed loan reopens (RESTRUCTURED if it was, else to the state its dates give), its application goes back to ACTIVE (`PAYMENT_REVERSED` event), and any level the closure gave is taken back (`CLOSURE_REVERSED`). Audited as a `PAYMENT_REVERSAL` `balance_adjustment`.
- **Refund cases.** `POST /api/refund-cases/{id}/refunded {utr, refundedOn}` and `/cancel {reason}`, ACCOUNTANT or ADMIN, OPEN only (else 409 `STALE_STATE`).

API (staff, under `/api/collections`): `GET|POST /loans/{loanId}/emi` (panel `{loanId, eligible, reasonCode, owedTodayPaise, previewForToday[], current, history[]}` / request `{acceptBy, note}`), `GET /emi?status=REQUESTED` (ADMIN queue), `POST /emi/{id}/approve`, `/reject {reason NOT_ELIGIBLE|SETTLE_INSTEAD|PAYMENT_HISTORY|OTHER, note}`, `/cancel {reason}`. Borrower: `GET /api/loans/{loanId}/emi` → `{id, loanId, status, version, acceptBy, expiresAt, instalmentCount, totalPaise, instalments[{seq, dueDate, dueAt, principalPaise, chargesPaise, totalPaise, paidPaise, status}], interestMode, acceptedAt, declinedAt, activatedAt}` or null (the plan for accepting today while APPROVED, the schedule once ACTIVE); `POST /api/loans/{loanId}/emi/{id}/otp`, `/accept {otp, consentVersion, version}` (idempotent once ACTIVE), `/decline {reason NOT_NOW|DID_NOT_ASK, version}`. EMI instalments are paid through the normal `POST /api/loan/{id}/repayments`.

Tests:
- `EmiConversionServiceTest`: `cc20_sevenThousandInThreeInstalmentsSumsExactly`, `cc20_everyLadderAmountAndChargeSumsExactly` (property over the ladder × late charges), instalment count from config, `anEmiApprovedByANonAdminIs422`, `theRequesterCannotApprove`, `aRequestIsRefusedWhileTheFlagIsOff`, only collections requests, eligibility (`NOT_OVERDUE`, `EXTENSION_OPEN`, `RESTRUCTURED`). `LoanExtensionLedgerTest.ext02_…` gains `EMI_OPEN`. `RepaymentServiceTest`: `cc18_aFullRepaymentClosesWithClosureTypeRepaid` (and SETTLED on the settlement test), `cc05_paidOnTheDueDateButVerifiedWhileOverdueWritesALateReversedAdjustment`, `cc05_aLatePaymentWritesNoReversal`, `cc10_theVerifierCannotReverseThePayment`, `cc10_onlyAVerifiedRepaymentCanBeReversed`. `PaymentPurposeGuardTest`: `ext30_…` and a self-check that it catches a missing filter.
- `ApplicationFlowIntegrationTest`: `emi_requestApproveAcceptSplitsWhatIsOwedAndCloses` (₹7,000 loan 3 days late: flag OFF refused → request → 409 → extension `EMI_OPEN` → Head approve 422 → requester SOD → ADMIN approves, RESTRUCTURE_PENDING → borrower sees 3 instalments, ₹7,420 → wrong OTP refused → accept, replay idempotent → RESTRUCTURED, BULLET superseded + EMI active, instalments 233333+14000 / 233333+14000 / 233334+14000, one `balance_adjustment`, one idempotency key, no late charge after → instalment 1 paid → rest paid, CLOSED, REPAID, level 1); `emi_aDeclinedOfferPutsTheLoanBackToOverdue` (decline, then expiry by the sweep); `cc10_reversingTheClosingPaymentReopensTheLoanAndTakesBackTheLevel`; `cc05_paidOnTheDueDateVerifiedLateIsOnTime`; `cc17_overdueInstalmentAndRefundCaseActions`.

Deviations:
- **What an EMI converts.** Everything owed on the conversion date (open principal + late charge to date), split into principal and charges per instalment; nothing is waived. `emi.penal` is off and not configurable yet: no late charge accrues on a RESTRUCTURED loan (CC-17's penal and the collection-case reopen wait for a decision). Only `NONE` interest mode exists; another mode needs the breakdown to carry EMI interest.
- **Request scope.** Collections requests on any overdue loan (no case-assignment check, which lives in navix-collections); the ADMIN approval is the gate. No EMI notifications or `EMI_ADDENDUM` PDF yet (the borrower sees the offer through `GET /api/loans/{id}/emi`); both are Phase 10/11 work with the screens.
- **Reversal endpoint path.** Under the existing `/api/loan/{loanId}/repayments/{paymentId}/reverse` (not `/payments/{id}/reverse`). Roles ACCOUNTANT/ADMIN (FINANCE arrives in Phase 7); maker-checker is "not the recorder or verifier", not a second approval step. Extension payments are not reversible here (they moved a due date), nor a closed loan whose customer has another loan open (`ANOTHER_LOAN_OPEN`). The reversed payment's allocation rows are deleted (copied into the adjustment) so every allocation sum stays VERIFIED-only. A re-closure issues no second closure receipt.
- **Refund case SoD.** None: the case is opened by the system, so there is no maker to separate from.
- **Application reopen.** `ApplicationStatus.CLOSED` stays terminal in the transition map (other checks rely on it); `reopenForLoan` is the one writer that leaves it.
- **Customer purge.** `CustomerService.deleteCustomer` does not yet clear the Phase 6 tables (allocations, extensions, schedules, EMI, adjustments); account deletion is designed later (records retained, then purged).

For Phase 7 / 10 / 11:
- Phase 7 migrations start at **V105** (complaint V105, `balance_adjustment` extension V106, roles V107; Phase 8's `refresh_token` V108). `balance_adjustment` kinds written so far: `EXTENSION`, `EMI`, `LATE_REVERSED`, `PAYMENT_REVERSAL`.
- Phase 10: Restructure queue = `GET /api/collections/emi` (ADMIN) + the loan panel; "Refunds owed" can use `/api/refund-cases` actions; the Accountant gets a Reverse action on verified repayments.
- Phase 11 (EMI screens): render from `status` (APPROVED → plan + OTP accept/decline; ACTIVE → instalments with `status` DUE/OVERDUE/PAID); unknown values → S9; send `version` on accept/decline.

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

**Status (2026-11-01): done.** `mvn verify` is green (1,975 unit, up from 1,957) and `mvn verify -Pit` is green (96 integration, up from 91; V105–V107 applied to Testcontainers Postgres). Migrations (renumbered: 6b/6c took V103/V104):
- **V105 `complaint`:** `holiday_calendar(day, name)` (ADMIN-maintained, CC-64); `complaint` (ticket `DN-GRV-00001…` from a sequence default, `customer_id`, `loan_id`, category `PAYMENT|CHARGES|COLLECTIONS_CONDUCT|LOAN_DISPUTE|EXTENSION_NOT_REQUESTED|DATA_PRIVACY|APP_OR_SERVICE|OTHER`, `body`, `attachment_key`, `source BORROWER|STAFF|SYSTEM`, status `OPEN|ACKNOWLEDGED|IN_REVIEW|RESOLVED|ESCALATED`, `ack_due_at`/`review_due_at`/`resolve_due_at`, the milestone timestamps, `resolution_note` (required to resolve), `assigned_to`, one `*_breached_at` per milestone, `version`); append-only `complaint_event` and `loan_hold_event` (a shared `append_only()` trigger).
- **V106 `balance_adjustment`:** extended, not recreated: `balance_adjustment_reason(code, label)` lookup (EXTENSION, EMI, PAID_ON_TIME, PAYMENT_REVERSED, SETTLEMENT_APPROVED, REFUND_PAID, REFUND_CANCELLED, MANUAL_CORRECTION, WRITE_OFF) with a foreign key and `reason_code not null`, a `note` column (6c's free-text reversal reasons moved there), a check on `kind`, and the append-only trigger.
- **V107:** `RECOVERY`, `SUPPORT`, `FINANCE`, `COMPLIANCE` in the `staff_user`/`staff_invite` role checks; `staff_totp_secret(staff_id, secret_enc, confirmed_at, last_step)`.

Code:
- **Complaints (`ComplaintService`, navix-loan).** SLA dates from `ComplaintSla` (pure): end of the 2nd and 5th working day after the IST filing date (weekends and `holiday_calendar` skipped), end of the 15th calendar day. Borrower: `POST /api/complaints {category, body, loanId?, attachmentKey?}` (required `Idempotency-Key`, CC-22; the loan must be theirs), `GET /api/complaints`, `GET /api/complaints/{id}` (another customer's ticket is 422 `FORBIDDEN_OWNER`); the view is `{id, ticketNo, loanId, category, body, status, createdAt, ackDueAt, resolveDueAt, acknowledgedAt, resolvedAt, resolutionNote, grievanceOfficer{name, phone, email}}` with the officer from `navix.grievance.*`. Staff (under `/api/staff`, SUPPORT and ADMIN act, COMPLIANCE reads): `GET /complaints?status=&category=&assignedTo=&customerId=&breached=true`, `GET /complaints/{id}` (ticket + events), `POST /complaints` (file for a caller), `/{id}/acknowledge {note?, version?}`, `/{id}/assign {staffId}`, `/{id}/status {status, note?, version?}`, `/{id}/resolve {note, version?}`, `/{id}/escalate {note, version?}`. Moves follow a transition map; a stale version or a move the status does not allow is 409 `STALE_STATE`; resolve and escalate need a note (`NOTE_REQUIRED`). Holidays: `GET|PUT|DELETE /api/admin/holidays/{yyyy-mm-dd}` (ADMIN writes).
- **SLA sweep.** `ComplaintSlaSweep` (navix-app, every 15 min, `scheduler_lock`, `navix.complaints.sla-cron`) flags each missed milestone once (`update … returning`) and raises `ComplaintSlaBreachedEvent`.
- **Notifications.** `COMPLAINT_RECEIVED` (borrower IN_APP + EMAIL: ticket, reply-by date, grievance officer), `COMPLAINT_RESOLVED` (borrower), `COMPLAINT_SLA_BREACHED` (assignee + ADMINs). Every template can use `{brand}`, `{brandWebsite}`, `{supportPhone}`, `{supportEmail}`, `{grievanceName}`, `{grievancePhone}`, `{grievanceEmail}`, filled by `TemplateRenderer` from config.
- **Disputes (`LoanDisputeService`).** `POST /api/loans/{id}/dispute {reason, complaintId?}` and `POST /api/loans/{id}/dispute/release {note}` (ADMIN, SUPPORT, COLLECTION_HEAD; maker-checker: whoever raised a dispute cannot release it, 422 `SOD_VIOLATION`), `GET /api/loans/{id}/holds` (also COMPLIANCE). A `LOAN_DISPUTE` ticket on an open loan raises one in the ticket's transaction (`ComplaintFiledEvent`). Raising cancels an extension still on offer (OFFERED/APPROVED/ACCEPTED, reason `DISPUTED`, `ExtensionDirectory.cancelOffer`) and an open EMI offer (an approved one puts the loan back to its overdue state); offers are refused while disputed (`ON_HOLD`, already in the eligibility checks) (EXT-27). A closed loan is 422 `LOAN_CLOSED`, a loan already on hold 409.
- **CC-71.** A borrower declining an extension with `DID_NOT_ASK` opens an `EXTENSION_NOT_REQUESTED` ticket (`ComplaintService` hears `ExtensionDeclinedEvent` in the same transaction); EMI decline with `DID_NOT_ASK` does the same.
- **balance_adjustment writers.** Extension (6b), EMI (6c), late reversal (6c), payment reversal (now `PAYMENT_REVERSED` + note), and new: settlement approval (`SETTLEMENT`, through `LoanDirectory.recordAdjustment`), refund paid / cancelled (`REFUND`).
- **Roles.** `StaffRole` gains `RECOVERY`, `SUPPORT`, `FINANCE`, `COMPLIANCE`. COMPLIANCE is read-only at one place, `JwtAuthFilter`: any request but GET/HEAD/OPTIONS outside `/api/auth/` is 403 `READ_ONLY_ROLE`. SUPPORT gets no money or due-date right (every such endpoint is role-allowlisted; tested). FINANCE joins ACCOUNTANT on payment reversal, refund-case list and actions, and the transactions / pending-repayments reads (COMPLIANCE may read those and refund cases). `web/src/lib/auth/rbac.ts` mirrors the names (labels, purpose, `customer:view` only).
- **Staff MFA (`StaffMfaService`).** RFC 6238 TOTP (SHA-1, 30 s, 6 digits, ±1 step), secret AES-256-GCM encrypted with `navix.mfa.encryption-key` (`NAVIX_MFA_ENCRYPTION_KEY`, base64 of 32 bytes), a used step cannot be replayed. `POST /api/auth/staff/login` takes `totpCode`; with `navix.mfa.enforce` on (default), ADMIN, FINANCE, CREDIT_HEAD and DISBURSEMENT_HEAD get `MFA_ENROLMENT_REQUIRED` until enrolled, then `MFA_REQUIRED` / `INVALID_MFA_CODE`; anyone enrolled is asked for a code. A missing or wrong code counts against the three sign-in attempts. Enrolment before the first MFA sign-in: `POST /api/auth/staff/mfa/enrol {email, password}` → `{secret, otpauthUri}`, `POST /api/auth/staff/mfa/confirm {email, password, code}`; ADMIN reset: `POST /api/admin/staff/{id}/mfa/reset` (never their own). `ProductionConfigGuard` refuses `NAVIX_ENV=prod` with enforcement off or without a valid key.
- **Brand sweep.** No "DhanBoost" left in backend `src/main` outside migrations: notification templates use `{brand}`/`{brandWebsite}`, the HTML email shell, OTP SMS (`navix.sms.otp-template` default now `{brand}`), email OTP, password reset, credit brief PDF, referral and DSA copy take `navix.brand.*`; OpenAPI title from config; internal verification rows record provider `INTERNAL`; NACH narration "Loan repayment"; comments and POM descriptions say DhanNow. `BrandGuardTest` fails on any new occurrence. The JWT carries no issuer claim, so nothing changed for tokens.
- **Customer purge.** `CustomerService.deleteCustomer` (ADMIN) is refused while a loan is open (`LOAN_OPEN`) and for anyone with a loan or complaint on file (`RECORDS_RETAINED`); otherwise it also clears the V100 offer documents and the credit level.

Tests:
- `ComplaintSlaTest`: `cc64_aMidweekComplaintIsAcknowledgedInTwoWorkingDaysAndReviewedInFive`, `cc64_aFridayComplaintSkipsTheWeekend`, `cc64_aWeekendComplaintStartsCountingOnMonday`, `cc64_holidaysFromTheCalendarAreNotWorkingDays`, `cc64_theDayIsTheIstDateNotTheUtcDate`.
- `StaffMfaServiceTest` (RFC 6238 vectors, ±1 step accepted and ±2 refused, replay, encryption at rest and wrong key, required roles, enforcement switch, enrolment and confirm), `AuthControllerTest` (password-only sign-in blocked for a required role and the attempts are spent; the code reaches the check), `ProductionConfigGuardTest.prod_refusesStaffMfaOffOrWithoutAValidKey`, `CustomerServiceTest.deleteCustomerIsRefusedWhileALoanIsOpenAndWhenRecordsMustBeKept`, `BrandGuardTest`.
- `ApplicationFlowIntegrationTest`: `complaint_fileAcknowledgeReviewResolveAndTheSlaSweep` (ticket number, replay, key required, cross-customer `FORBIDDEN_OWNER`, borrower kept off the staff queue, COMPLIANCE reads and is 403 on write, collections 422, acknowledge → review → resolve with a note, 409 on an illegal move, events append-only, breach flagged once); `dispute_aLoanDisputeComplaintHoldsTheLoanAndCancelsTheOpenOfferThenRelease` (EXT-27: approved offer cancelled, `ON_HOLD` on a new offer, 409 on a second dispute, borrower 422, release, SoD on a staff-raised dispute, four hold events, offers work again); `cc64_theSlaSkipsHolidaysFromTheAdminCalendar`; `cc71_didNotAskOnAnExtensionOpensAComplaint`; `roles_supportIsMoneyBlockedComplianceIsReadOnlyAndAdjustmentsAreAudited` (SUPPORT 422 on admin-record, reverse, refund and extension offer; COMPLIANCE 403 on write, reads refunds; FINANCE pays a refund → `REFUND`/`REFUND_PAID` adjustment; unknown reason code refused; adjustments append-only).

Deviations:
- **Migration numbers.** V105–V107 instead of V104–V106. Phase 8 starts at **V108** (`refresh_token`).
- **Holiday calendar.** Built here (small table, ADMIN endpoints) because CC-64 is a Phase 7 test; there was no calendar before.
- **Dispute from a complaint.** Automatic for a borrower's (or SUPPORT's) `LOAN_DISPUTE` ticket on an open loan, as the plan says; resolving the ticket does not lift the hold, staff release it. A dispute leaves an extension with a payment already on file (PAYMENT_PENDING) for the Accountant, and never touches an ACTIVE one.
- **Attachments.** `attachment_key` is stored as given (length and `..` checked); a borrower upload category and the staff presigned view arrive with the app screen (Phase 11) and console (Phase 10).
- **No manual correction endpoint yet.** The reason codes `MANUAL_CORRECTION` and `WRITE_OFF` are seeded; the endpoints are Phase 10b / CC-66. The CC-06 `paid_on` correction is not built.
- **COMPLIANCE reads.** Writes are blocked everywhere; reads are only what existing role lists allow plus the complaint queue, holds, refunds and the transaction ledger. Console-wide read access comes with the Phase 10 screens. RECOVERY has no rights of its own yet.
- **Web.** The new roles are mirrored in `rbac.ts` with `customer:view` only; their dashboard view and screens are Phase 10. Web vitest is green (907); `tsc --noEmit` reports one error that predates this phase (`src/lib/staff/performance-page.test.tsx:90`, a Vitest type).
- **Brand sweep limits.** DLT-registered SMS text changed with the brand, so the SMS templates (and the WhatsApp template name `dhanboost_otp`, sender `DHANBT`) must be re-registered for DhanNow before go-live (Phase 13 checklist). The DSA lead emails still describe the navix salary product apart from the brand (the DSA SMS was reworded); they need a DhanNow rewrite if DSAs are used. Historical rows with provider "DhanBoost" are left as they are.
- **Customer purge.** Kept as an ADMIN tool for people who never had a loan; retention-aware deletion and the purge job are Phase 9 (CC-45).

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

**Status (2026-11-01): done.** `mvn verify` is green (2,003 unit, up from 1,975) and `mvn verify -Pit` is green (106 integration, up from 96; V108–V110 applied to Testcontainers Postgres). Migrations (renumbered from V108):
- **V108 `refresh_token`:** `customer_id`, `device_id`, `family_id` (one per device sign-in), `token_hash` (SHA-256, unique), `expires_at`, `revoked_at`, `revoke_reason` (`ROTATED|REUSED|LOGOUT|DEVICE_SIGNED_OUT|ALL_DEVICES`).
- **V109 `otp_challenge` + `attempt_counter`:** `otp_challenge(purpose, subject_hash, code_hash, expires_at, attempts)` unique per purpose+subject; `attempt_counter(key, window_start, count, blocked_until)`. Subjects, codes and limiter keys are HMAC-SHA256 under `navix.auth.secret`: no mobile, email or code is readable from either table.
- **V110 `device_token`:** `(customer_id, device_id)` key, `fcm_token`, `platform`, `app_version`, `device_name`, `last_seen`.

Code:
- **App sessions (`RefreshTokenService`, `AuthController`).** A borrower sign-in (`/borrower/login`, `/borrower/password-login`) that names a `deviceId` (plus optional `deviceName`, `appVersion`) gets a 15-minute access JWT carrying a `did` claim and a 30-day refresh token bound to that device; the response adds `refreshToken`, `expiresIn` (seconds) and `refreshExpiresAt`. `POST /api/auth/borrower/refresh {refreshToken, deviceId}` rotates (the old token is spent); a spent token sent again revokes the whole family (`REFRESH_REUSED`, CC-39); an unknown, expired, signed-out or other-device token is `REFRESH_INVALID` (another device also revokes the family). `POST /api/auth/borrower/logout {refreshToken?, allDevices?}` ends that device's family and forgets its push token; `allDevices` with a borrower bearer ends them all (CC-38). Every revocation commits in its own transaction before the error is thrown.
- **Devices (`DeviceService`, `DeviceController`, app board D7).** `PUT /api/devices/me {fcmToken, platform, appVersion, deviceName}` (the device comes from the token's `did`; another device is `DEVICE_MISMATCH`; a token moving to this device is taken off any other row), `GET /api/devices/me` → `[{deviceId, name, platform, appVersion, lastSeen, current}]` (only devices with a live refresh token), `DELETE /api/devices/me/{deviceId}` revokes that device's refresh tokens and deletes its row. Borrower only (`FORBIDDEN_ROLE`).
- **Play Integrity (`CaptchaVerifier`, `IntegrityVerifier` port, `PlayIntegrityClient`).** A request with `X-Client: android` is judged by its `X-Integrity-Token` while the `play-integrity` flag is ON: Google's `decodeIntegrityToken` with the service account, then our package (`navix.integrity.package-name`, `com.dhannow.app`), `PLAY_RECOGNIZED`, `MEETS_DEVICE_INTEGRITY` and a token at most 5 minutes old; a failure is `INTEGRITY_FAILED`. With the flag OFF the header buys nothing: the request goes through Turnstile like the web, and only `navix.integrity.allow-android-without-check=true` (default false, refused in prod) lets it skip. No service account means every token fails (fail closed).
- **OTP and limiter state in Postgres (`AuthStateStore` → `JdbcAuthStateStore`).** `AttemptLimiter`, `BorrowerOtpService` and `EmailOtpService` keep their logic and interfaces; each read-modify-write is row-locked in its own transaction, so caps hold across instances and survive a restart, and a rejected attempt is never rolled back with its rejection. This covers every OTP purpose (login, bureau consent, extension, EMI). Expired rows are purged on OTP sends. CC-36: `/borrower/otp/request` adds per-IP (30/hour) and per-device (10/hour) caps; an app request whose integrity check failed (CC-42, no Play Services) still gets an OTP under strict caps (10 and 3). Not applied in mock-SMS mode (nothing is sent).
- **Server caps.** Selfie: 3 captures per application (`SELFIE_ATTEMPTS_EXCEEDED`), counted on the SELFIE row and reset when staff reopen or rule on it. DigiLocker: 3 consent sessions per 24 hours (was 5), then the manual Aadhaar upload (CC-33). Signup penny drop: `POST /verify/penny-drop` goes through `PennyDropGuard` (lock → `PENNY_DROP_LOCKED`, every non-PASS a strike, CC-27).
- **Push (`FcmSender`, `PushClient` port, `FcmPushClient`).** `NotificationChannel.PUSH`, shown with the in-app title and body, data `{type}` for the app's router (CC-53). Skipped (`PUSH_DISABLED`) while `push-fcm` is OFF, `NO_DEVICE`/`NOT_BORROWER` otherwise; an FCM "unregistered" answer forgets the token (CC-61). FCM HTTP v1 with the service account (`GoogleServiceAccount`: RS256 JWT exchanged for an access token, cached). `EXTENSION_OFFERED` and `EXTENSION_ACTIVATED` now also go to PUSH.
- **App links.** `navix.app.frontend-base-url` defaults to `https://dhannow.com`. `RedirectAllowlist` (navix-common): DigiLocker init and e-sign init refuse any return URL outside `navix.app.redirect-allowlist` (default `https://dhannow.com/app/cb/`; scheme, host and port exact, path prefix, no user info or `..`) with `REDIRECT_NOT_ALLOWED` (CC-34); outside prod `navix.app.redirect-allowlist-dev` (default `http://localhost:3000/`) is added for the web. `JourneyView` adds `stepKey` (`signup.<step>`, `offer.<step>`, `status`); `route` is kept.
- **Liveness (`LivenessPort`, `ManualReviewLiveness`).** `POST /api/applications/{id}/verify/selfie/frames {straightKey, rightKey, leftKey}` (three distinct keys from `/presign-upload` with docType `SELFIE`, each checked to be this application's) files the frames as `SELFIE`, `SELFIE_RIGHT`, `SELFIE_LEFT` documents and writes the SELFIE row `REVIEW` (provider `MANUAL_REVIEW`, `derived.frameStraight/Right/Left`, `selfieAttempts`). Staff queue (`SelfieReviewService`, credit team and ADMIN): `GET /api/staff/selfie-reviews` → `[{applicationId, customerId, borrowerName, submittedAt, attempts, frames{straight, right, left}, message}]` (short-lived links), `POST /{applicationId}/approve {note?}` → PASS, `POST /{applicationId}/reject {reason}` (required, `NOTE_REQUIRED`) → FAIL, then the step is reopened and the journey rewound so the borrower retakes it with fresh tries. Both through `manualDecision`, so the override is on the audit trail; a case no longer in REVIEW is `STALE_STATE`.
- **CORS (`WebConfig`).** A `CorsConfigurationSource` applied by `http.cors()`: `navix.cors.allowed-origins` (default `https://dhannow.com,https://staff.dhannow.com`), plus `navix.cors.dev-origins` (default `http://localhost:3000`) outside prod.
- **`GET /api/app/config` (public, CC-52).** `{minVersion, latestVersion, maintenance, flags{loan-extension, emi-conversion, payment-gateway, play-integrity, push-fcm, digio-mandate, onboarding-paused}, brand, website, support{name, phone, email}, grievance{name, phone, email}, legal[{key, title, url, version}]}`, contacts from `navix.brand.*`/`navix.grievance.*`, legal pages from `navix.mobile.legal` (defaults: the eleven Phase 10a W1 pages on `https://dhannow.com`, `version` null until W2).
- **Prod guard.** `ProductionConfigGuard` refuses `allow-android-without-check=true`, and `push-fcm` or `play-integrity` ON without `navix.google.service-account-path`.

Tests:
- `MobilePlatformIntegrationTest` (new): `cc39_refreshRotatesAndReuseRevokesTheFamily`, `logoutEndsThisDevicesSession`, `cc38_devicesListSignOutADeviceAndLogOutOfAll` (two phones, FCM token registered, `current`, sign out the other, then all), `theWebLoginIsUnchanged`, `cc36_otpStateSurvivesARestartAndLimitsHoldAcrossInstances` (code from one instance verifies on a fresh one, single use, five wrong guesses over three instances lock it, limiter cap shared, nothing stored in clear), `cc34_digilockerRedirectToAnotherHostIsRefused`, `selfieFrames_goToTheStaffQueue_approveAndReject`, `selfieFrames_theFourthSubmissionIsRefused`, `cors_allowsTheDhanNowSitesAndRefusesOthers`, `cc52_appConfigIsPublicAndCarriesVersionsFlagsAndContacts`.
- Unit: `AuthControllerTest` (app sign-in issues a device-bound short token and a refresh token, the web login is unchanged, refresh, logout and all devices, `cc36_otpRequestsAreCappedPerIpAndPerDevice`, last XFF hop), `CaptchaVerifierTest` (integrity ON pass/fail/missing, OFF falls back to Turnstile unless opted in, web untouched, the verdict rule incl. CC-43 device integrity and freshness), `RedirectAllowlistTest` (`cc34_…`), `WebConfigTest`, `FcmSenderTest` (flag off, every device, dead token forgotten, skips, failure, extension types on PUSH with the in-app wording), `JourneyServiceTest` (`stepKey`), `ApplicationVerificationServiceTest` (frames → REVIEW with three documents, foreign or repeated keys refused, the 3-capture cap and reset on reopen, `cc27_signupPennyDropRespectsTheLockAndCountsAFailure`, `cc33_…fourthSession…`), `ProductionConfigGuardTest` (both new refusals). The OTP and limiter unit tests run over an in-memory `AuthStateStore`.

Deviations:
- **Migration numbers.** V108–V110 instead of V107–V109. Phase 9 starts at **V111**.
- **The web keeps its token.** A sign-in without `deviceId` (the web BFF) still gets the one 7-day token and no refresh token; the plan's "BFF uses refresh too" is not built because Phase 10 removes the borrower web. An access token is not revoked server-side: logout and device sign-out end the refresh token, and the access token lapses within 15 minutes.
- **Table shapes.** `otp_challenge` has no `sent_count_window`/`blocked_until`: the send ration stays in the limiter (`attempt_counter`, as before) and a challenge with 5 misses is dropped. `refresh_token` adds `family_id` and `revoke_reason`; `device_token` adds `device_name` and `created_at`.
- **Integrity scope.** Checked on the routes that already had a challenge (password sign-ins, forgot-password) and used for the stricter OTP caps. The OTP request itself is not refused without a passing token, because CC-42 (no Play Services) must still get an OTP. CC-43 (failed verdict: view and repay only, no new application) needs a session claim and an application gate and is left to Phase 11. The token's `requestHash` is not bound to the request yet.
- **Per-IP cap and the web.** The BFF forwards no client address, so web OTP sends share the BFF's IP cap (30/hour) until the borrower web goes in Phase 10.
- **Selfie reject.** The borrower is not notified; they see the reopened step on their next `/journey`. The reject only reopens while the application is SANCTIONED.
- **App links on the site.** `/.well-known/assetlinks.json` and `app/cb/*` pages are `site/` work (Phase 10a W3). Links built from `frontend-base-url` (password reset, outreach, mandate) now point at dhannow.com paths that only exist once Phase 10a W8 adds `app/open`.
- **Not built here.** `customer_id` as `DN` + sequence (CC-24, listed for this phase in the catalogue): the derived id with the `borrower_mobile` collision guard stays; moved to Phase 9 with account deletion. The new-device SMS alert (CC-38). The demo `navix-onboarding` `OtpService` (not used by borrower auth) stays in memory.
- **Found: `*IT` classes never run.** Surefire only picks up `*Test` classes, so `SecurityMatrixIT`, `LeadImportJobPersistenceIT`, `LeadOutcomeAndNotePersistenceIT`, `MandateDashboardIT` and `MandateDebitRepositoryIT` are not in the 106 (the new test is named `…IntegrationTest` for that reason). Run alone, `SecurityMatrixIT` already fails on the baseline (4 tests: `onboarding-paused` is seeded ON, and a borrower on the staff namespace now gets 403, not 401). Phase 9 should rename them, or add `**/*IT.java` to the `it` profile's includes, and fix those tests.
- **Deployment must set:** `NAVIX_GOOGLE_SERVICE_ACCOUNT_PATH` (before turning on `push-fcm` or `play-integrity`), `NAVIX_CORS_ORIGINS` if the sites move, `NAVIX_REDIRECT_ALLOWLIST`, `NAVIX_APP_BASE_URL`, `NAVIX_APP_MIN_VERSION`/`NAVIX_APP_LATEST_VERSION`/`NAVIX_APP_MAINTENANCE`; optional `NAVIX_APP_ACCESS_TTL_SECONDS` (900), `NAVIX_APP_REFRESH_TTL_DAYS` (30), `NAVIX_INTEGRITY_PACKAGE`, `NAVIX_SITE_BASE_URL`. `AUTH_SECRET` now also keys the OTP/limiter hashes: rotating it voids live OTPs and counters (harmless).

For Phase 9 / 10 / 11:
- Phase 9 migrations start at **V111**.
- Phase 11 (app): send `deviceId` (install UUID), `deviceName`, `appVersion` on sign-in; keep `refreshToken` in encrypted storage and refresh on 401 or before `expiresIn`, one refresh at a time (two parallel refreshes with one token look like theft and end the session); headers `X-Client: android`, `X-Integrity-Token` (on sign-in, forgot-password and OTP request); `PUT /api/devices/me` with the FCM token after sign-in and on token change; route from `stepKey`; selfie via three presigns + `/verify/selfie/frames`; `GET /api/app/config` at launch.
- Phase 10: Selfie review screen = `/api/staff/selfie-reviews`.

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
