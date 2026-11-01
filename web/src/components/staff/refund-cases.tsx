"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { ActionButton, DeskTable, istDate } from "@/components/staff/desk-kit";
import { Badge } from "@/components/ui";
import { paiseToINR } from "@/lib/api/applications";
import { refundCasesApi, type RefundCase } from "@/lib/api/staff-desks";
import { formatDate, formatDateTime } from "@/lib/utils";

const KEY = ["refund-cases"];

/**
 * Refund cases (PLAN Phase 6a/6c): money a borrower paid beyond what they owed (OVERPAYMENT) or a NACH debit
 * that landed after the loan closed. ACCOUNTANT / FINANCE mark one refunded (UTR + date) or cancel it with a
 * reason; COMPLIANCE reads. Each action writes a `REFUND` balance adjustment.
 */
export function RefundCases({ canManage }: { canManage: boolean }) {
  const [status, setStatus] = React.useState("OPEN");
  const q = useQuery({ queryKey: [...KEY, status], queryFn: () => refundCasesApi.list(status || undefined) });
  const total = (q.data ?? []).filter((r) => r.status === "OPEN").reduce((s, r) => s + r.amountPaise, 0);
  return (
    <section className="space-y-2">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="mb-0 text-base">Refund cases</h2>
        <select className="field" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Refund status">
          <option value="OPEN">Open</option>
          <option value="REFUNDED">Refunded</option>
          <option value="CANCELLED">Cancelled</option>
          <option value="">All</option>
        </select>
        {status === "OPEN" && <span className="text-sm text-muted">Owed: <span className="font-mono font-semibold text-navy">{paiseToINR(total)}</span></span>}
      </div>
      <DeskTable<RefundCase>
        query={q}
        rowKey={(r) => r.id}
        empty="No refund cases."
        columns={[
          { label: "Loan", render: (r) => <span className="font-mono">#{r.loanId}</span> },
          { label: "Amount", render: (r) => <span className="font-mono font-semibold">{paiseToINR(r.amountPaise)}</span> },
          { label: "Why", render: (r) => (r.reason === "OVERPAYMENT" ? "Paid more than owed" : "Debit after closure") },
          { label: "Opened", render: (r) => formatDateTime(r.createdAt) },
          {
            label: "Status",
            render: (r) => (
              <span>
                <Badge variant={r.status === "OPEN" ? "warning" : r.status === "REFUNDED" ? "success" : "neutral"}>{r.status.toLowerCase()}</Badge>
                {r.refundUtr && <span className="block font-mono text-xs">UTR {r.refundUtr}{r.refundedOn ? ` · ${formatDate(r.refundedOn)}` : ""}</span>}
                {r.cancelReason && <span className="block text-xs text-muted">{r.cancelReason}</span>}
              </span>
            ),
          },
          {
            label: "",
            render: (r) =>
              canManage && r.status === "OPEN" ? (
                <div className="flex gap-2">
                  <ActionButton
                    label="Mark refunded"
                    variant="gold"
                    fields={[
                      { name: "utr", label: "Refund UTR", required: true },
                      { name: "refundedOn", label: "Bank date", type: "date", required: true, placeholder: istDate() },
                    ]}
                    run={(v) => refundCasesApi.refunded(r.id, v.utr.trim(), v.refundedOn)}
                    invalidate={KEY}
                    success="Refund recorded"
                  />
                  <ActionButton
                    label="Cancel"
                    fields={[{ name: "reason", label: "Why nothing is owed back", required: true }]}
                    run={(v) => refundCasesApi.cancel(r.id, v.reason)}
                    invalidate={KEY}
                    success="Refund case cancelled"
                  />
                </div>
              ) : null,
          },
        ]}
      />
    </section>
  );
}
