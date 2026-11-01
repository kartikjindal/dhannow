/**
 * The Repayment tab's "how it adds up" ledger, in integer paise.
 *
 * The server's OutstandingView is the source of every figure — nothing here recomputes them; this only
 * lays them out (PLAN Phase 10b R8): principal, late charge, and interest, which on DhanNow terms was
 * deducted at disbursal ("prepaid") and is never owed. Received money is shown settling the late charge
 * first, then principal, as the server's allocator does.
 */
import { daysBetween } from "@/lib/calc/loan-math";
import { isoDayToLocalDate } from "@/lib/customers/customer-360";
import { loanGraceDays, type LoanView, type OutstandingView, type PaymentView } from "@/lib/api/applications";

export type LedgerKey = "principal" | "interest" | "penalty" | "total";

export interface LedgerRow {
  key: LedgerKey;
  label: string;
  /** If held to the due date. */
  payablePaise: number;
  /** Owed as of today. */
  dueTodayPaise: number;
  /** Verified payments. */
  receivedPaise: number;
  outstandingPaise: number;
  /** Days the amount was charged over (interest / penalty rows). */
  days?: number;
  /** Days the "due today" figure was charged over (server interestDays / penaltyDays). */
  dueDays?: number;
  /** Interest deducted at disbursal: shown for the record, never part of the totals. */
  prepaid?: boolean;
}

export interface Ledger {
  rows: LedgerRow[];
  daysSinceDisbursal: number | null;
  daysToDue: number | null;
  /** Due date + the loan's grace days: interest still runs, no penalty. Null when the loan has no grace. */
  graceDay: Date | null;
  isOverdue: boolean;
  daysOverdue: number;
}

export function buildLedger(input: {
  loan: LoanView;
  outstanding: OutstandingView | null;
  payments: PaymentView[];
  today: Date;
}): Ledger {
  const { loan, outstanding, payments, today } = input;
  const closed = loan.status === "CLOSED";

  const principal = loan.principalPaise;
  // DhanNow terms: the whole tenure's interest was deducted at disbursal (R4). Navix terms accrue it instead.
  const prepaidInterest = loan.upfrontInterestPaise ?? outstanding?.interestPrepaidPaise ?? null;
  const payableInterest = prepaidInterest != null ? 0 : loan.totalRepayablePaise - principal;
  const payableTotal = loan.totalRepayablePaise;

  const verifiedSum = payments
    .filter((p) => p.loanId === loan.id && p.status === "VERIFIED")
    .reduce((s, p) => s + p.amountPaise, 0);
  const received = outstanding?.verifiedPaise ?? (closed ? payableTotal : verifiedSum);

  const dueInterest = outstanding?.interestPaise ?? (prepaidInterest != null || closed ? payableInterest : Math.max(0, loan.outstandingPaise + received - principal));
  const duePenalty = outstanding?.penaltyPaise ?? 0;
  const dueTotal = principal + dueInterest + duePenalty;

  // Display split of the verified total: late charge, then interest (navix terms only), then principal.
  let left = received;
  const take = (due: number) => {
    const t = Math.min(left, due);
    left -= t;
    return t;
  };
  const rPenalty = take(duePenalty);
  const rInterest = take(dueInterest);
  const rPrincipal = take(principal);

  const totalOutstanding = closed ? 0 : (outstanding?.outstandingPaise ?? Math.max(0, dueTotal - received));

  const disbursed = isoDayToLocalDate(loan.disbursedOn);
  const due = isoDayToLocalDate(loan.dueDate);
  const daysToDue = due ? daysBetween(today, due) : null;
  const isOverdue = !closed && daysToDue != null && daysToDue < 0;
  const graceDays = loanGraceDays(loan);
  const graceDay = due && graceDays > 0 ? new Date(due.getFullYear(), due.getMonth(), due.getDate() + graceDays) : null;

  return {
    rows: [
      {
        key: "principal", label: "Principal", payablePaise: principal,
        dueTodayPaise: principal, receivedPaise: rPrincipal, outstandingPaise: closed ? 0 : principal - rPrincipal,
      },
      {
        key: "penalty", label: "Late charge", payablePaise: 0,
        dueTodayPaise: duePenalty, receivedPaise: rPenalty, outstandingPaise: closed ? 0 : duePenalty - rPenalty,
        dueDays: outstanding?.penaltyDays,
      },
      prepaidInterest != null
        ? {
            key: "interest", label: "Interest (prepaid at disbursal)", payablePaise: prepaidInterest,
            dueTodayPaise: 0, receivedPaise: prepaidInterest, outstandingPaise: 0, prepaid: true,
            days: disbursed && due ? daysBetween(disbursed, due) : undefined,
          }
        : {
            key: "interest", label: "Interest", payablePaise: payableInterest,
            dueTodayPaise: dueInterest, receivedPaise: rInterest, outstandingPaise: closed ? 0 : dueInterest - rInterest,
            days: disbursed && due ? daysBetween(disbursed, due) : undefined,
            dueDays: outstanding?.interestDays,
          },
      {
        key: "total", label: "Total owed", payablePaise: payableTotal,
        dueTodayPaise: dueTotal, receivedPaise: received, outstandingPaise: totalOutstanding,
      },
    ],
    daysSinceDisbursal: disbursed ? daysBetween(disbursed, today) : null,
    daysToDue,
    graceDay,
    isOverdue,
    daysOverdue: isOverdue && daysToDue != null ? -daysToDue : 0,
  };
}
