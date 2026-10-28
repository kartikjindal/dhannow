# DhanNow vs navix_final: loan-engine difference document

What has to change in the navix_final backend (Spring Boot, `com.navix.*`) so it runs the DhanNow product.

**Sources read**

| DhanNow side | navix_final side |
|---|---|
| `DHAN_NOW_KFS_Information_Policy.docx` (DN-LGL-022 v1.0 draft) | `navix-loan/.../service/LoanMath.java` |
| `DHAN_NOW_Sanction_Letter_Template.docx` | `RepaymentService.java`, `LoanService.java`, `ApplicationFlowService.java`, `OfferService.java` |
| `Document (11).docx`: DhanNow website and policy content, including the Loan Agreement Access Policy section (a separate `DHAN_NOW_Loan_Agreement_Access_Policy.docx` was not found on this machine) | `navix-loan/.../pdf/SanctionLetterPdfRenderer.java` |
| `Dhan_Now_Payday_Loan_IT_Handover.pptx` (v1) and `Dhan_Now_Detailed_Payday_Loan_IT_Handover_v2.pptx` (v2, 16 Oct 2026) | `navix-collections/...`, `navix-income-risk/.../LimitCalculator.java`, `application.yml`, `frontend/src/lib/calc/loan-math.ts` |

All DhanNow documents are marked **draft, pending Legal, Compliance, Tax and lender approval**. The handover deck asks for every commercial value to be **configurable and versioned, with feature flags off until approval**. This document treats that as a requirement.

---

## 1. The five changes that matter most

1. **Tenure and due date.** navix uses a salary-linked due date, up to 40 days. DhanNow uses a **fixed 14 calendar days from disbursal**.
2. **Interest timing.** navix accrues 1%/day and collects it at repayment. DhanNow **deducts 14 days of interest upfront** together with the fee and GST. The borrower **repays only the principal** on day 14.
   - ₹1,000 loan: fee ₹100 + GST ₹18 + interest ₹140 = ₹258 deducted. Net disbursal is **₹742**. Day-14 repayment is **₹1,000**.
3. **Limits.** navix sets the limit at 25% of salary, up to ₹10L. DhanNow uses a **credit ladder: ₹1k → 2k → 3k → 4k → 5k → 7k → 10k**. The first loan is ₹1,000, and the customer moves up one level only after a full on-time or early repayment.
4. **New lifecycle features.**
   - **Loan extension (collections-led).** ₹258 per ₹1,000 plus late charge buys 14 more days, up to 3 times; offered only by collections after a call.
   - **Recovery-only EMI conversion.** Admin-approved, with versioned schedules.
   - **New statuses:** DUE_TODAY, OVERDUE_D1, OVERDUE_REVIEW, RESTRUCTURE_PENDING, RESTRUCTURED, DISPUTED and FRAUD_REVIEW.
5. **Documents and disclosure.**
   - A **separate KFS** with APR and cooling-off details.
   - A sanction letter matching the new template, with validity and acceptance deadline.
   - A **loan agreement** with authenticated access.
   - The **lender of record** named on every document.
   - **DhanNow branding and grievance details** replacing DhanBoost.

---

## 2. Area-by-area difference

Legend: **Keep** = no change. **Change** = modify existing code. **New** = build. **Config** = move a hardcoded value into versioned config.

### 2.1 Product and pricing math

| Rule | navix_final today | DhanNow requires | Backend change |
|---|---|---|---|
| Money unit and rounding | Integer paise, HALF_UP to the paisa (LoanMath:202) | “Consistent rounding”; Finance to define. Tables show whole rupees | **Keep** paise. **Config**: rounding rule, decided by Finance |
| Processing fee | 10% of principal, hardcoded (LoanMath:33) | 10% of principal | **Config**: `pricing.pfRate`, versioned |
| GST | 18% of fee, hardcoded (LoanMath:36) | 18% of fee, pending tax confirmation | **Config**: `pricing.gstRate` |
| Interest rate | 1%/day simple on original principal (LoanMath:39) | 1%/day simple on original principal, no compounding | **Config**: `pricing.dailyRate` |
| When interest is charged | Accrued daily and collected at repayment. `totalRepayable = P + interest` (LoanMath:97) | **14 days of interest deducted upfront at disbursal** | **Change**: new pricing mode `UPFRONT_INTEREST`. `netDisbursal = P − PF − GST − P×rate×tenure`. `totalRepayable = P` |
| Net disbursal | P − PF − GST (LoanMath:84) | P − PF − GST − 14-day interest | **Change** (follows from the row above) |
| Prepayment | Interest only to the day paid, so early payment saves money (RS:329) | Interest is already collected upfront. No rebate unless the agreement says so (“Do not promise a refund of interest”) | **Change**: early repayment = principal only, no rebate by default. **Config** flag `pricing.prepayRebate=false` |
| Tenure | Disbursal → latest salary date within 40 days (LoanMath:169-199). Max 40, fallback 30 | **Fixed 14 calendar days from the recorded disbursal date** | **Change**: `dueDate = disbursedOn + tenureDays`, with `tenureDays=14` in **Config**. Remove salary-day logic for DhanNow. Define the timezone (IST) and holiday handling (open) |
| Grace day | 0 for new loans. Legacy loans get 1 day with 1 extra interest day (LoanMath:62) | None stated. Overdue starts the first day after the due date | **Keep** 0. Drop the legacy path for DhanNow |
| Credit-team repayment date | `approvedRepaymentDate` stored but ignored at disbursal (LoanService:49) | N/A, the date is a fixed rule | **Change**: remove or ignore. The sanction letter must show disbursal + 14 |
| Min/max amount | Min ₹1,000. Max ₹10L ceiling (LoanMath:48, 71) | Min ₹1,000, max ₹10,000. Only the **7 ladder amounts** are selectable | **Change** + **Config**: `product.allowedAmounts=[1000,2000,3000,4000,5000,7000,10000]` |
| Limit logic | 25% of monthly salary (LoanMath:119, LimitCalculator:29). Risk A/B/C/D multipliers exist but aren’t wired. Credit exec can sanction any amount ≥ ₹1,000 (AFS:672) | **Credit ladder levels L1-L7**. First loan = L1 (₹1,000). Up one level only after full on-time/early repayment. Late = same level. No skipping without approval | **New**: `CreditLevel` on the customer, with level history, an upgrade service and a manual-exception approval. The sanction amount is capped by the level, not by salary |
| APR | Not computed anywhere | Must be computed with the prescribed method and shown in KFS and sanction letter | **New**: `AprCalculator` (cash flows: −net at T0, +repayments at due dates; IRR annualised). Method to be confirmed by Compliance |
| Pricing version on the offer | Not stored | “Retain the version of the pricing configuration used to generate a customer’s offer” | **New**: `pricing_config` table, with `pricing_version_id` stored on the offer, loan and KFS |

**Worked check (DhanNow, all levels):**

| Principal | PF | GST | Interest (14d) | Deduction | Net | Day-14 repay |
|---|---|---|---|---|---|---|
| ₹1,000 | 100 | 18 | 140 | 258 | 742 | 1,000 |
| ₹2,000 | 200 | 36 | 280 | 516 | 1,484 | 2,000 |
| ₹3,000 | 300 | 54 | 420 | 774 | 2,226 | 3,000 |
| ₹4,000 | 400 | 72 | 560 | 1,032 | 2,968 | 4,000 |
| ₹5,000 | 500 | 90 | 700 | 1,290 | 3,710 | 5,000 |
| ₹7,000 | 700 | 126 | 980 | 1,806 | 5,194 | 7,000 |
| ₹10,000 | 1,000 | 180 | 1,400 | 2,580 | 7,420 | 10,000 |

These are the UAT expected values from the handover deck. Add them as unit tests for the new `LoanMath`.

### 2.2 Late payment, overdue and collections

| Rule | navix_final today | DhanNow requires | Backend change |
|---|---|---|---|
| Penal charge | 2%/day on principal, capped at 30 days (LoanMath:42-45) | **Not defined.** It must be “permitted under the agreement and law” and disclosed in the KFS | **Config**: `penal.enabled=false` until approved. Rate, base and cap all go in config. Late charge accrued before an extension is collected once, inside the extension amount (decided 27 Oct 2026) |
| Interest after due date | Stops at the due date. Only penalty accrues after | Not stated. Interest is collected upfront for 14 days only | **Keep**: no post-due interest unless the agreement says so |
| Overdue states | Loan reads OVERDUE on access. Sweep → IN_COLLECTIONS. Nothing sets DEFAULTED or WRITTEN_OFF (ApplicationStatus:105) | `DUE_TODAY` → `OVERDUE_D1` (day 1) → `OVERDUE_REVIEW` (repeated or substantial delay) → `CLOSED` / `RESTRUCTURE_PENDING` / `DISPUTED`. `FRAUD_REVIEW` restricts new disbursal | **New** loan-status machine (section 2.5). Keep DPD buckets for reporting |
| Effect on the ladder | Reloan blocked if any payment was more than 5 days late (AFS:1436) | **Any lateness ≥1 day = no upgrade** (stay at the same level). A permanent ban only under an approved policy | **Change**: on-time is judged against the due date and time with no tolerance. Keep the 5-day block only if the risk policy approves it |
| Disputes | None | `DISPUTED`/`MANUAL_REVIEW` pauses automated adverse actions | **New**: dispute case on the loan, plus a pause flag checked by reminders, collections and auto-debit |
| Recovery conduct | Collections module, officers, settlements (SettlementService) | Fair-practice rules only, with no agent rules defined | **Keep** the module. Reminder and overdue templates need compliance approval |

### 2.3 Loan extension, collections-led (new; decided 27 Oct 2026)

Nothing like this exists in navix today. It is built on the navix collections maker-checker (`CollectionPaymentService`, `SettlementService`).

- **Trigger:** a borrower calls collections. There is **no self-serve button** in the app. Allowed from the due date onward (DUE_TODAY / OVERDUE_*), with no dispute, fraud hold, pending payment or restructure.
- **Charge:** fee 10% + GST 18% on the fee + 14 days × 1%/day interest (₹258 per ₹1,000), **plus the late charge accrued up to the pay-by date**.
- **New due date:** previous due date + 14 days, at 23:59 IST. The principal stays owed.
- **Cap:** 3 per loan (config `extension_max`). A 4th needs an ADMIN override with a reason code.
- **Approvals:** the officer offers, the Collection Head approves (proposer ≠ approver), the borrower confirms in the app with OTP, and the Accountant verifies the payment. Verification is the only step that moves the due date.
- **Payment:** the borrower pays in the app (`repayments` with `purpose=EXTENSION`), or the officer logs it (`CollectionPaymentKind.EXTENSION`). Both are verified by the Accountant.
  - Short payment → no extension.
  - Excess → refund case.
- **Navix change:** `CollectionPaymentService.validate` credits principal for every kind today. For `EXTENSION` it must instead call a new `LoanDirectory.applyExtension` (new schedule version, due + 14, income booking), leaving the principal ledger untouched.
- **Data:**
  - new `loan_extension` table;
  - `loan.extension_count`, `loan.original_due_date`;
  - `payment.purpose`, `payment.extension_id`;
  - `collection_payment.extension_id`;
  - `product_config.extension_max`.
- **Documents:** an extension addendum PDF (new due date, charges, APR) for each extension, OTP-accepted.
- **Money model (from the navix code read, 28 Oct 2026):** navix `RepaymentService.breakdown` derives interest and penalty from the due date and subtracts every verified payment. Moving the due date or counting extension money as a payment would silently change what is owed. DhanNow owed = principal − verified *repayment* principal + late charge of the current due window. Extension money is kept out of that sum (`payment.purpose`), and a `loan_due_history` table keeps each cycle's lateness.
- **Other navix behaviours the extension must handle:**
  - `markInCollections` is one-way, so activation resets the status to ACTIVE or reminders stop.
  - The worklist's +7-day horizon would hide extended loans.
  - There is no `@Version` or lock on payments: activation runs under the loan advisory lock NACH already uses.
  - Settlements and extensions are mutually exclusive.
  - About 25 report queries need a purpose filter.
- **Credit ladder:** an extended loan never raises the level.
- **Open legal and tax risk:** re-charging PF and GST on the same principal without a new disbursal needs written Legal/tax approval before launch.

### 2.4 Recovery-only EMI conversion (new)

- **Trigger:** overdue, substantial delay, normal recovery attempts failed. Never automatic.
- **Request:** the Recovery team raises it with the recovery history attached. **They cannot approve.**
- **Approval:** the **authorised Admin/Backend role only**, enforced server-side (RBAC), not just hidden in the UI.
- **Schedule (proposed):**
  - ≤ ₹5,000 → 2 instalments on Day 10 and Day 20.
  - ₹7,000 and ₹10,000 → 3 instalments on Day 10, 20 and 30.
  - Day 0 = conversion date.
  - Deterministic paise rounding so the instalments sum exactly (UAT: ₹7,000 / 3).
- **Interest and charges:** only what the contract permits. Never capitalise or charge twice. The website copy (“Principal × 1% × 20, two equal EMIs”) conflicts with the deck and needs a Legal decision.
- **Activation:** after approval **and** customer acceptance. A new schedule version, with the original kept.
- **Data model:** `repayment_schedule` and `schedule_instalment` tables (none exist today: navix has a single bullet repayment on the loan row).

### 2.5 Loan lifecycle and statuses

| navix_final ApplicationStatus / LoanStatus | DhanNow status (handover slide 15) | Mapping / change |
|---|---|---|
| DRAFT, KYC_PENDING | APPLICATION_SUBMITTED, KYC_PENDING / UNDERWRITING | Rename or map. Keep the intake journey |
| CREDIT_EXEC_PENDING | UNDERWRITING | Map |
| SANCTIONED | APPROVED / AGREEMENT_PENDING | Map. Add offer **validity** and **acceptance deadline** (sanction template) |
| DISBURSEMENT_PENDING | DISBURSEMENT_PENDING | Keep |
| DISBURSED → ACTIVE | ACTIVE / CURRENT | Keep |
| (computed OVERDUE) | **DUE_TODAY**, **OVERDUE_D1**, **OVERDUE_REVIEW** | **New**, set by a daily job at a defined due time and timezone |
| IN_COLLECTIONS | OVERDUE_REVIEW | Map |
| (none) | **RESTRUCTURE_PENDING**, **RESTRUCTURED** | **New** (EMI). An extension returns the loan to ACTIVE with a new due date |
| (none) | **DISPUTED**, **FRAUD_REVIEW** | **New** |
| CLOSED | CLOSED (read-only) | Keep. Add a closure receipt |
| DEFAULTED, WRITTEN_OFF (never set) | not in the deck | Decide with Finance whether they are needed |

Also required by the deck:
- **Separate dimensions:** lifecycle status, repayment status, credit level and restructure status. navix mixes them across ApplicationStatus and LoanStatus.
- **Disbursal confirmation.** navix lets the Disbursement Head type a txnRef with no accountant confirmation (AFS:925). DhanNow requires the loan to go ACTIVE only after **confirmed** disbursal, so the reconciliation step must come back.

### 2.6 Payments and reconciliation

| Rule | navix_final today | DhanNow requires | Change |
|---|---|---|---|
| Payment counted when | Borrower uploads UTR + screenshot → PENDING_VERIFICATION → accountant verifies (RS:102-166) | “Received only after verified reconciliation, not because the customer uploaded a screenshot” | **Keep** the flow. It already matches |
| Payment methods | Manual UPI QR / bank transfer + optional Digio NACH | UPI, debit cards, net banking, Airtel Payments Bank “where available”. A payment gateway is implied | **New**: payment-gateway integration with webhook reconciliation (the documents don’t name a gateway). Keep the manual flow as a fallback |
| Allocation waterfall | None. Payments are summed against one total (RS:35) | An “approved waterfall” is required. Order not stated | **New**: allocation engine (e.g. penal → interest → principal). Order to be decided by Finance |
| Part payment | Allowed and flagged partial | Allowed. Does not close the loan and does not count for an upgrade | **Keep**. Add the “no upgrade on partial” rule |
| Duplicate callbacks | Dedupe on (loanId, txnRef) (RS:87) | Idempotency for gateway callbacks and schedule generation | **Change**: idempotency keys on payment webhooks and on extension/EMI activation |
| Auto-debit | Digio NACH. Debits only at DPD 1/3/5/9, flag off, provider mock (MandateDebitScheduler) | eNACH “only through an authorised process”. Whether it is mandatory is unresolved. A failed debit doesn’t cancel the obligation | **Decision needed**: present on Day 14? The mandate ceiling (2× limit today) must be re-set for the ladder |
| Refunds | None. Overpayment register only | Refunds follow policy, with no promised timelines | **New**: refund case for overpayments and cooling-off exits |
| Closure receipt / NOC | Only a LOAN_CLOSED SMS | “Confirmation of repayment and loan closure” | **New**: closure receipt PDF + notification after reconciliation |

### 2.7 Documents and disclosure

| Item | navix_final today | DhanNow requires | Change |
|---|---|---|---|
| KFS | Combined into one PDF titled “SANCTION LETTER · KEY FACT STATEMENT” (SanctionLetterPdfRenderer:209). `LoanDocumentType.KFS` unused | A **separate KFS** in the RBI format, given **before** contract execution. It covers: lender legal name, amount, tenure, rate + method, fee, GST, other charges, **net disbursal, APR**, schedule, penal charges, **cooling-off period**, and grievance details | **New** `KfsPdfRenderer` from the same versioned pricing snapshot. Show it before e-sign (app screen B2) |
| Sanction letter | Fields: principal, fee, GST, net, interest, tenure, dates, masked account, grievance | The template adds: letter no., application no., loan account no., **offer validity, acceptance deadline**, lender legal name and address, purpose, **APR**, other charges, total deductions, total payable, repayment frequency, first and final due date, penal, **cooling-off and exit**, prepayment/foreclosure, repayment beneficiary, conditions precedent, and borrower acceptance with date and time | **Change** the renderer to the new template. Remove line 143 (“no auto-debit mandate”), which contradicts the mandate flow |
| Loan agreement | No separate agreement. The e-signed PDF is the sanction letter | A loan agreement that the customer accepts by e-sign. The record includes parties, **document version**, and date and time of acceptance | **New** agreement template + `LoanDocument(AGREEMENT)` rows with version |
| Loan Agreement Access Policy | `GET /applications/{id}/documents` with an owner check (ApplicationController:669) | Access through the authorised app journey, **authenticated** (identity re-check allowed), with no cross-customer access. Download or retain copies | **Change**: a dedicated `/loans/{id}/documents` endpoint for KFS, sanction letter, agreement, schedule and receipts. Short-lived signed URLs. Optional OTP/biometric step-up before showing |
| Lender of record | Not in backend or PDF. The frontend has placeholder NBFCs (Arthveda, Sentinel) | “Actual lender must be clearly identified” on KFS and all loan documents. The website names **MLB Securities Pvt Ltd** as NBFC partner, but the lender disclosure is still a placeholder | **Config**: `lender.legalName/address/registration`, used by every document. **Do not ship placeholders** |
| Cooling-off / look-up | Not supported (“cooling-off” in code means reapply blocks) | Disclose the period, exit amount and allowed fee retention | **New**: cooling-off window on the loan, exit quote, exit payment and closure path |
| Consent records | Bureau consent text + OTP, T&C version + time, PEP time | Date/time, notice version, purpose, affirmative action, authentication evidence, withdrawal, mandate reference | **Change**: add notice version, purpose and withdrawal to the consent records |

### 2.8 Brand, entity and grievance

| Item | navix_final today | DhanNow | Change |
|---|---|---|---|
| Brand on PDFs, SMS, email | “DhanBoost” (renderer:208, yml:259/287) | **DHAN NOW**, support 9211331169, support@dhannow.com, www.dhannow.com | **Config**: brand block |
| Operating entity | NAVIX Finance Pvt Ltd (CIN U64990HR2026PTC144926) | Same CIN. Registered office: Plot 268, Sector 33, Gurugram 122001 | Keep. Add the address |
| Grievance officer | Lalit Kumar / 8510028510 / grievance@dhanboost.com (yml:295). Frontend has a different phone | **Dharmendra Rajput / 9211331170 / grievance@dhannow.com**. The Actual Lender block names Lalit Kumar, a 9-digit number, and nodal officer Vipin Kumar (likely an error) | **Config**. Confirm which officer belongs to the lender and which to the platform |
| Grievance timelines | Not in code | Acknowledge in 2 working days, review in 5, resolve in 15 calendar days. Escalate to the lender, then RBI CMS | **New**: complaint ticket with SLA timers (the app’s “Raise a complaint”) |

### 2.9 Roles, audit and config

- **Roles (deck slide 16):**
  - Customer: cannot edit terms.
  - Recovery: can request EMI but not approve it.
  - Support: cannot change balance or due date.
  - Admin/Backend: approves restructuring.
  - Finance: reconciles payments.
  - Compliance: read-only logs.

  navix has Credit Exec/Head, Disbursement Head, Accountant, Collections and Admin. **Add the Recovery/Support/Finance/Compliance permission split**, and **MFA for privileged roles**.
- **Audit fields** required on every schedule or balance change: loan ID, customer ID, original and revised schedule version, principal, paid, outstanding, fee/tax/interest breakdown, **calculation version**, approver ID, reason code, timestamps, acceptance evidence, reconciliation ID. navix has `BaseAuditEntity` (who and when) only. **Add the adjustment and reason-code table.**
- **Feature flags to add** (all OFF at launch except `loan-extension`): `loan-extension` (ON), `emi-conversion`, `penal-charges`, `prepay-rebate`, `cooling-off-exit`, `payment-gateway`.
- **Move to versioned config:** every value in `LoanMath`, `LimitCalculator` and `loan-math.ts`. Today they are Java/TS constants.

### 2.10 Reloan

| navix_final | DhanNow |
|---|---|
| One live loan at a time. Needs a prior CLOSED loan. PRE_APPROVED → SANCTIONED carries over the old sanction amount | One live loan at a time is **not stated** (decision needed). **Next ladder level** after an on-time repayment, the **same level** after a late one. “Fresh eligibility, affordability and fraud checks” on every new loan. Limit increases need consent and lender approval |
| Reject cool-offs: 90/90/365/30 days | Not specified. Keep as risk policy only if approved |

---

## 3. Impact on the Android app wireframe

| Screen | What changes for DhanNow |
|---|---|
| B1 Choose amount | Slider → **ladder chips** (only the current level and below). Breakdown adds **interest deducted upfront**: Net = ₹742 for ₹1,000 |
| B2 Repayment date | Calendar shows **disbursal + 14 days**. No longer credit-team set |
| B5 Loan summary | Repayment = principal only. Add APR and the cooling-off line |
| New: KFS screen | Separate KFS before signing, with Download |
| B8 Agreement | Three documents: KFS, sanction letter, loan agreement. Name the **lender of record** |
| C1 Home / C3 Repay | Due amount = principal (no daily interest growth). Remove “pay early, save interest” unless a rebate is approved. Add **level progress** (“Repay on time to unlock ₹2,000”) |
| New | Extension confirm (OTP), pay extension charge, extension verified, extended dashboard; staff offer/approve/verify screens. EMI proposal acceptance (flagged) |
| D3 Support | DhanNow contacts, grievance officer Dharmendra Rajput, complaint ticket with SLA |

---

## 4. Decisions needed before build

1. **Lender of record:** MLB Securities or another lender, with verified RBI registration. The documents currently contradict each other.
2. **Legality of upfront interest** (a 14-day interest deduction) and the **APR method**.
3. **Penal charges:** rate, base and cap, or none.
4. **Loan extension:** product rules decided (collections-led, cap 3, previous due + 14, late charge collected). Still open: Legal/tax approval for re-charging PF and GST.
5. **EMI conversion:** interest treatment (website: 20 days × 1%; deck: principal only + permitted charges). What “substantial delay” means.
6. **Payment waterfall** order, gateway choice, and whether auto-debit is presented on Day 14.
7. **Cooling-off period** length and exit amount.
8. **Eligibility thresholds:** age, income, salaried only or not. The website says none should be published unconfirmed.
9. **Whether more than one live loan** is allowed, and whether the 5-day-late reloan block stays.
10. **Due-time cut-off** (e.g. 23:59 IST) and holiday handling for Day 14.
11. **Grievance officer for the platform vs the lender:** Dharmendra Rajput vs Lalit Kumar / Vipin Kumar.

---

## 5. Suggested build order

1. Versioned `pricing_config` + `product_config` and the new `LoanMath` (upfront interest, 14-day tenure, ladder amounts), with unit tests from the table in section 2.1.
2. Credit level + upgrade service, replacing the salary-based limit for DhanNow.
3. Loan status machine (DUE_TODAY / OVERDUE_D1 / OVERDUE_REVIEW / DISPUTED / FRAUD_REVIEW) + daily job + disbursal confirmation.
4. Documents: KFS renderer, new sanction letter, loan agreement, `/loans/{id}/documents`, lender and brand config.
5. Payments: allocation waterfall, gateway + webhook idempotency, closure receipt, refunds.
6. Schedules table + collections-led loan extension + EMI conversion (Admin RBAC, flag OFF).
7. Grievance tickets with SLA, audit adjustment table, MFA for privileged roles.
