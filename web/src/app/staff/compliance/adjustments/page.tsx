"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { DeskTable } from "@/components/staff/desk-kit";
import { adjustmentsApi, type BalanceAdjustment } from "@/lib/api/staff-desks";
import { formatDateTime } from "@/lib/utils";

const KINDS = ["EXTENSION", "EMI", "LATE_REVERSED", "PAYMENT_REVERSAL", "SETTLEMENT", "REFUND", "MANUAL_CORRECTION", "WRITE_OFF"];

/**
 * Compliance · Balance-adjustment log (PLAN Phase 7): every change to what a borrower owes or when, other
 * than a plain payment — extensions, EMI, reversals, settlements, refunds. Append-only; read-only here.
 */
export default function AdjustmentLogPage() {
  const can = useCan();
  const [loanId, setLoanId] = React.useState("");
  const [kind, setKind] = React.useState("");
  const q = useQuery({
    queryKey: ["balance-adjustments", loanId, kind],
    queryFn: () => adjustmentsApi.list({ loanId: loanId ? Number(loanId) : undefined, kind: kind || undefined }),
  });
  if (!can("adjustments:view")) return <NoAccessNotice message="Compliance, Finance and Admin read the adjustment log." />;
  return (
    <div className="space-y-5">
      <PageHeader title="Balance-adjustment log" subtitle="Every change to a balance or due date, with who approved it" />
      <div className="flex flex-wrap gap-2 text-sm">
        <input className="field" inputMode="numeric" placeholder="Loan #" aria-label="Loan number" value={loanId} onChange={(e) => setLoanId(e.target.value.replace(/\D/g, ""))} />
        <select className="field" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind">
          <option value="">All kinds</option>
          {KINDS.map((k) => <option key={k} value={k}>{k.replace(/_/g, " ").toLowerCase()}</option>)}
        </select>
      </div>
      <DeskTable<BalanceAdjustment>
        query={q}
        rowKey={(a) => a.id}
        empty="No adjustments match."
        columns={[
          { label: "When", render: (a) => formatDateTime(a.at) },
          { label: "Loan", render: (a) => <Link className="font-mono" href={`/staff/customers/${a.customerId}`}>#{a.loanId}</Link> },
          { label: "Kind", render: (a) => a.kind.replace(/_/g, " ").toLowerCase() },
          { label: "Reason", render: (a) => a.reasonLabel ?? a.reasonCode },
          { label: "Note", render: (a) => a.note ?? "—" },
          { label: "Before", render: (a) => <code className="block max-w-xs whitespace-pre-wrap break-all text-xs">{a.before}</code> },
          { label: "After", render: (a) => <code className="block max-w-xs whitespace-pre-wrap break-all text-xs">{a.after}</code> },
          { label: "Approved by", render: (a) => a.approverName ?? (a.approverId ? `#${a.approverId}` : "—") },
        ]}
      />
    </div>
  );
}
