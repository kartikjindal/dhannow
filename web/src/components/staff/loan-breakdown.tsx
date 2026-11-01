"use client";

import * as React from "react";
import {
  paiseToINR,
  type ApplicationView,
  type LoanView,
  type OutstandingView,
} from "@/lib/api/applications";
import { useQuery } from "@tanstack/react-query";
import { creditLevelApi } from "@/lib/api/staff-desks";
import { formatDate } from "@/lib/utils";

/**
 * The shared loan cost-breakdown `<dl>` — principal → fee/GST → net disbursed →
 * total repayable → accrued interest/penalty → paid → outstanding. Extracted
 * from the retired loan dialog so the journey step popups, drawer and detail
 * page render identical figures.
 *
 * `outstanding` (the penalty/prepayment-aware `OutstandingView`) is optional:
 * when absent the itemized interest/penalty/paid rows are omitted and the
 * outstanding falls back to the loan's cached `outstandingPaise`.
 */
export function LoanBreakdown({
  loan,
  outstanding,
}: {
  loan: LoanView;
  outstanding?: OutstandingView | null;
}) {
  const out = outstanding;
  const prepaid = loan.upfrontInterestPaise ?? out?.interestPrepaidPaise ?? null;
  return (
    <dl className="grid grid-cols-2 gap-x-6 gap-y-1">
      <Row label="Principal" value={paiseToINR(loan.principalPaise)} />
      <Row label="Processing fee" value={paiseToINR(loan.processingFeePaise)} />
      <Row label="GST on fee" value={paiseToINR(loan.gstPaise)} />
      {/* R4/R8: interest is deducted at disbursal — shown, never owed. */}
      {prepaid != null && <Row label="Interest" value={paiseToINR(prepaid)} hint="Prepaid at disbursal" />}
      <Row label="Net disbursed" value={paiseToINR(loan.netDisbursedPaise)} />
      <Row label="Repayable on the due date" value={paiseToINR(loan.totalRepayablePaise)} />
      {/* Interest still accruing exists only on navix (ACCRUED) terms; DhanNow loans show none. */}
      {out && (out.interestPaise ?? 0) > 0 && (
        <Row
          label={out.interestDays != null ? `Interest (${out.interestDays}d)` : "Interest"}
          value={paiseToINR(out.interestPaise ?? 0)}
        />
      )}
      {out && (out.penaltyPaise ?? 0) > 0 && (
        <Row
          label={out.penaltyDays != null ? `Late charge (${out.penaltyDays}d)` : "Late charge"}
          value={paiseToINR(out.penaltyPaise ?? 0)}
        />
      )}
      {out && <Row label="Paid (verified)" value={paiseToINR(out.verifiedPaise ?? 0)} />}
      <Row
        label={out ? "Amount due today" : "Outstanding"}
        value={paiseToINR(out ? out.outstandingPaise : loan.outstandingPaise)}
        strong
      />
      {out?.settledAmountPaise != null && (
        <Row label="Settlement (full & final)" value={paiseToINR(out.settledAmountPaise)} />
      )}
      <Row label="Disbursal confirmed on" value={loan.disbursedOn ? formatDate(loan.disbursedOn) : "—"} />
      <Row
        label="Repayment due"
        value={loan.dueDate ? formatDate(loan.dueDate) : "—"}
        hint={
          loan.extensionCount
            ? `Extended ${loan.extensionCount} of 3${loan.originalDueDate ? ` · first due ${formatDate(loan.originalDueDate)}` : ""}`
            : "14 days after the confirmed disbursal, 23:59 IST"
        }
      />
      {loan.closedOn && <Row label="Closed on" value={formatDate(loan.closedOn)} />}
    </dl>
  );
}

function Row({
  label,
  value,
  strong,
  hint,
}: {
  label: string;
  value: string;
  strong?: boolean;
  hint?: string;
}) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-muted">
        {label}
        {hint && <span className="block text-xs text-muted/80">{hint}</span>}
      </dt>
      <dd className={strong ? "font-semibold text-navy" : "text-ink"}>{value}</dd>
    </div>
  );
}

/**
 * The offer's figures before disbursal, for the requested amount, priced by the backend on the ACTIVE terms
 * (`/api/staff/customers/{id}/credit-level` quotes) — the console never computes money (PLAN Phase 10b R7).
 */
export function ProjectedCostBreakdown({ app }: { app: ApplicationView }) {
  const amount = app.amountRequestedPaise;
  const q = useQuery({
    queryKey: ["credit-level", app.customerId],
    queryFn: () => creditLevelApi.get(app.customerId),
    enabled: amount != null && app.customerId != null,
  });
  if (amount == null) {
    return <p className="text-muted">No amount has been requested yet.</p>;
  }
  const b = q.data?.quotes.find((x) => x.principalPaise === amount);
  if (!b) {
    return <p className="text-muted">{q.isLoading ? "Loading the offer figures…" : `${paiseToINR(amount)} is not a ladder amount.`}</p>;
  }
  return (
    <>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-1">
        <Row label="Principal" value={paiseToINR(b.principalPaise)} />
        <Row label="Processing fee" value={paiseToINR(b.feePaise)} />
        <Row label="GST on fee" value={paiseToINR(b.gstPaise)} />
        <Row label={`Interest (${b.tenureDays} days, upfront)`} value={paiseToINR(b.interestPaise)} />
        <Row label="Net disbursed" value={paiseToINR(b.netPaise)} />
        <Row label="Repayable on the due date" value={paiseToINR(b.repayPaise)} strong />
      </dl>
      <p className="mt-2 text-xs text-muted">
        Due {b.tenureDays} days after the confirmed disbursal, 23:59 IST.
      </p>
    </>
  );
}
