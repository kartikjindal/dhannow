# CLAUDE.md: DhanNow

Read this first. It explains what DhanNow is, where the plan lives, what is decided, and how to work in this repo.

## What this is

DhanNow is a short-term personal loan product: an Android app for borrowers, a staff console, and a public website. It is built by forking the navix_final platform (Spring Boot backend + Next.js) and changing its loan engine to the DhanNow product.

| Rule | Value |
|---|---|
| Tenure | 14 days from the **confirmed** disbursal date; due 23:59 IST on day 14, no holiday shift |
| Upfront deductions | 10% processing fee + 18% GST on the fee + 14 days × 1%/day interest |
| Example | ₹1,000 loan → ₹742 in the bank → repay ₹1,000 on day 14 |
| Repayment | Principal only |
| Late | 2%/day on principal, capped at 30 days |
| Credit ladder | ₹1k → 2k → 3k → 4k → 5k → 7k → 10k. First loan = L1. Up one level only after a full on-time repayment; any lateness = same level |
| Rollover / recovery EMI | Built, behind flags that are **OFF** |
| eNACH (Digio) | Code kept, flags **OFF** |
| Cooling-off | None in v1 |

All money is integer paise. All numbers come from versioned config (`pricing_config`, `product_config`); never hard-code them.

## Where things are

| Path | What |
|---|---|
| `docs/PLAN.md` | **The build plan.** Phases, migrations, APIs, corner cases (CC-01…CC-66), tests, go-live. Source of truth for scope |
| `docs/product/DhanNow_Loan_Engine_Difference.md` | navix vs DhanNow, area by area: what changes in the backend and why |
| `docs/product/*.docx` | DhanNow source documents (KFS policy, sanction letter template, website/policy content). Drafts, pending Legal/Compliance/lender approval |
| `docs/wireframes/` | The app wireframe: one `*.dc.html` per screen, layout in `canvas.json`. Live, reviewable copy: https://claude.ai/artifact/9mpCW1pqqzADmTcoVXNCqJ (private) |
| `docs/brand/` | Logo |
| `site/` | Public website for dhannow.com (static HTML). Pages are generated from `docs/product/Document (11).docx` by `site/build_pages.py` with `site/page_template.html`; `site/calc.js` is the public loan calculator (`node site/calc.js` runs its self-check) |
| `backend/`, `web/`, `app/`, `infra/` | **Not created yet.** Phase 0 of the plan creates them |

## Status

1. **Now:** wireframe under user review (app_navix UI kit, cobalt-blue accent). No product code is written until the user approves it.
2. **Next:** Phase 0 of `docs/PLAN.md`: fork navix `origin/app_navix` (`ae244485`) into `backend/` and `web/` (staff console only), baseline all navix tests green, then follow the phases in order.

## Decisions already made (do not re-ask)

| Topic | Decision |
|---|---|
| Repo | Monorepo |
| Namespace | Java `com.navix.*` / `navix-*` modules and `NAVIX_*` env vars stay; only the user-visible brand changes |
| Web | Static site `dhannow.com` (`site/`) + Android app for borrowers (no borrower web login in v1) + forked staff console at `staff.dhannow.com` |
| Infra | New separate AWS stack (staging + prod) |
| App | Flutter, Android only, minSdk 24, `com.dhannow.app`, English only but ARB/i18n-ready, lightweight (APK under 12 MB per ABI) |
| Auth | OTP + password; Play Integrity replaces captcha for the app; refresh tokens + logout; FCM push |
| Selfie | Guided straight/right/left capture → staff manual review; `LivenessPort` for a later ML/API check |
| Payments | Manual UPI/bank transfer + UTR + screenshot, verified by an accountant; gateway port scaffolded behind a flag |
| Disbursal | Disbursement Head enters the UTR; an Accountant confirms; only then does the loan go ACTIVE and day 0 start |
| On-time | Judged by the bank credit date of the payment (`paid_on`), not the verification time |
| Customer ID | Server-generated (`DN` + sequence). No profile/mobile/email changes at all in v1 |
| Lender of record | From config; prod refuses to boot while it is a placeholder |
| Hardening | Maker-checker everywhere; PII encrypted at rest + masked; no seeded passwords; OTP/limiter state in Postgres |
| Account deletion | In-app request; blocked while a loan is open; regulatory records retained, then purged |

## Design system

app_navix "Monochrome" UI kit (from navix_final `origin/app_navix`) with a **cobalt** accent.

| Token | Value |
|---|---|
| Page | `#F2F3F3` |
| Cards | `#FAFAFA`, 22px radius, soft layered shadow + white top edge |
| Tiles | `#FFFFFF` |
| Ink | `#0B0B0C` |
| Slate | `#4A4D52` |
| Line | `#E5E7E8` |
| Primary | `#1F5BDB` (buttons, progress) |
| Links / pressed | `#1A4BB8` |
| Tint | `#EEF3FF` |
| Success | `#17804F` / `#E3F7EC` |
| Error | `#C62A2F` / `#FDECEC` |
| Warning | `#8A6200` / `#FEF6D8` |

- **Type:** Bebas Neue for page titles and big amounts; Inter for everything else.
- **Shapes:** pill buttons; the active bottom-nav pill is ink.
- **Motion:** rise / growY / pop, turned off under reduced motion. Milestone animations are specified on wireframe board F4.

## How to work here

- **Plan first.** Every change maps to a phase in `docs/PLAN.md`. If something is not in the plan, add it to the plan before coding.
- **Reuse navix.** Reuse navix code wherever DhanNow does not differ; the plan lists what is reused unchanged.
- **Corner cases are tests.** Each `CC-xx` in the plan becomes a test with that name. A phase is done only when its CC tests pass.
- **Money parity.** Website `calc.js`, backend `LoanMath`, KFS/sanction/agreement PDFs and app screens must show identical figures. Tests enforce this.
- **One brand config.** Brand, support and grievance details come from one shared config. Never copy contact details by hand:

  | Item | Value |
  |---|---|
  | Support | 9211331169 / support@dhannow.com |
  | Grievance officer | Dharmendra Rajput, 9211331170, grievance@dhannow.com |

- **No NBFC claims.** Do not write anything about DhanNow being an NBFC. The lender of record is a config value.
- **Copy.** Plain language, sentence case, no "DhanBoost", no salary-day / 25%-of-salary / "pay early to save interest" wording (navix rules that do not apply).
- **Secrets.** Never commit secrets. Configuration comes from SSM `/dhannow/<env>/`. Local runs must not import cloud config.
