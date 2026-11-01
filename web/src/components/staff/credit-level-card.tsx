"use client";

/**
 * The customer's credit-ladder level (PLAN Phase 2 / 10b): level, maximum, next step and the history, plus the
 * ADMIN manual exception (a level and a reason code; recorded with the approver). Replaces the navix
 * "eligible limit from salary" card.
 */
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Gauge } from "lucide-react";
import { ErrorState, Skeleton, toast } from "@/components/ui";
import { paiseToINR } from "@/lib/api/applications";
import { creditLevelApi } from "@/lib/api/staff-desks";
import { errMessage } from "@/components/staff/pipeline/hooks";
import { formatDateTime } from "@/lib/utils";

const REASON_LABEL: Record<string, string> = {
  FIRST_LOAN: "First loan",
  ON_TIME_REPAID: "Repaid on time: up one level",
  LATE_REPAID_NO_CHANGE: "Repaid late: same level",
  EXTENDED_NO_CHANGE: "Extended: same level",
  MANUAL_EXCEPTION: "Manual exception",
  CLOSURE_REVERSED: "Closing payment reversed",
};

export function CreditLevelCard({ customerId, canSetException }: { customerId: number; canSetException: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["credit-level", customerId], queryFn: () => creditLevelApi.get(customerId) });
  const [level, setLevel] = React.useState("");
  const [reason, setReason] = React.useState("");
  const set = useMutation({
    mutationFn: () => creditLevelApi.setException(customerId, Number(level), reason.trim()),
    onSuccess: () => {
      setLevel("");
      setReason("");
      toast.success("Credit level updated");
      void qc.invalidateQueries({ queryKey: ["credit-level", customerId] });
    },
    onError: (e) => toast.error(errMessage(e)),
  });

  return (
    <section className="rounded border border-line bg-white p-4 shadow-sm">
      <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-navy">
        <Gauge size={16} /> Credit level
      </div>
      {q.isLoading ? (
        <Skeleton variant="line" rows={3} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data ? (
        <>
          <p className="text-sm">
            Level <span className="font-semibold text-ink">{q.data.current.level}</span> of {q.data.current.ladder.length} ·
            up to <span className="font-mono text-ink">{paiseToINR(q.data.current.maxPaise)}</span>
            {q.data.current.nextLevelPaise != null && (
              <span className="text-muted"> · next {paiseToINR(q.data.current.nextLevelPaise)} after an on-time repayment</span>
            )}
          </p>
          {q.data.history.length > 0 && (
            <ul className="mt-2 space-y-1 text-xs text-muted">
              {q.data.history.slice(0, 6).map((h, i) => (
                <li key={i}>
                  {formatDateTime(h.at)} · {h.fromLevel ?? "—"} → {h.toLevel} · {REASON_LABEL[h.reason] ?? h.reason}
                  {h.reasonCode ? ` (${h.reasonCode}${h.approvedByName ? `, ${h.approvedByName}` : ""})` : ""}
                  {h.reversedAt ? " · reversed" : ""}
                </li>
              ))}
            </ul>
          )}
          {canSetException && (
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <label className="text-xs">
                <span className="mb-1 block text-muted">Set level (admin exception)</span>
                <select className="field" value={level} onChange={(e) => setLevel(e.target.value)} aria-label="Level">
                  <option value="">Choose…</option>
                  {q.data.current.ladder.map((amount, i) => (
                    <option key={amount} value={i + 1}>
                      Level {i + 1} · {paiseToINR(amount)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-xs">
                <span className="mb-1 block text-muted">Reason code</span>
                <input className="field" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. RISK_REVIEW" />
              </label>
              <button
                type="button"
                className="btn btn-sm btn-gold"
                disabled={!level || !reason.trim() || set.isPending}
                onClick={() => set.mutate()}
              >
                Save exception
              </button>
            </div>
          )}
        </>
      ) : null}
    </section>
  );
}
