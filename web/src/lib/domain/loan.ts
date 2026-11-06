/**
 * The DhanNow loan states, mirrored exactly from the backend enums (PLAN Phase 10b R5):
 * `LoanLifecycle`, `LoanHold` and `RepaymentStatus` in `backend/navix-loan/.../domain/`. `loan.test.ts` reads
 * those files and fails on any drift. Staff screens show these, not the navix `status` column.
 */
import type { BadgeVariant } from "@/components/ui/badge";

export const LOAN_LIFECYCLE = [
  "DISBURSEMENT_PENDING",
  "ACTIVE",
  "DUE_TODAY",
  "OVERDUE_D1",
  "OVERDUE_REVIEW",
  "RESTRUCTURE_PENDING",
  "RESTRUCTURED",
  "CLOSED",
] as const;
export type LoanLifecycle = (typeof LOAN_LIFECYCLE)[number];

export const LOAN_HOLD = ["NONE", "DISPUTED", "FRAUD_REVIEW"] as const;
export type LoanHold = (typeof LOAN_HOLD)[number];

export const REPAYMENT_STATUS = ["UNPAID", "PARTIAL", "PAID_PENDING_VERIFICATION", "PAID"] as const;
export type RepaymentStatus = (typeof REPAYMENT_STATUS)[number];

export const LIFECYCLE_LABEL: Record<LoanLifecycle, string> = {
  DISBURSEMENT_PENDING: "Awaiting disbursal confirmation",
  ACTIVE: "Active",
  DUE_TODAY: "Due today",
  OVERDUE_D1: "Overdue",
  OVERDUE_REVIEW: "Overdue review",
  RESTRUCTURE_PENDING: "Restructure pending",
  RESTRUCTURED: "Restructured (EMI)",
  CLOSED: "Closed",
};

export const LIFECYCLE_TONE: Record<LoanLifecycle, BadgeVariant> = {
  DISBURSEMENT_PENDING: "info",
  ACTIVE: "success",
  DUE_TODAY: "warning",
  OVERDUE_D1: "error",
  OVERDUE_REVIEW: "error",
  RESTRUCTURE_PENDING: "warning",
  RESTRUCTURED: "info",
  CLOSED: "neutral",
};

export const HOLD_LABEL: Record<LoanHold, string> = { NONE: "No hold", DISPUTED: "Disputed", FRAUD_REVIEW: "Fraud review" };

export const REPAYMENT_STATUS_LABEL: Record<RepaymentStatus, string> = {
  UNPAID: "Unpaid",
  PARTIAL: "Part paid",
  PAID_PENDING_VERIFICATION: "Paid, awaiting verification",
  PAID: "Paid",
};

/** Label for whatever the backend sent (unknown values pass through rather than crash a screen). */
export function lifecycleLabel(s: string | null | undefined): string {
  return s ? (LIFECYCLE_LABEL as Record<string, string>)[s] ?? s : "—";
}
