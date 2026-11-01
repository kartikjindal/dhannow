"use client";

/**
 * Collections-tab sections for the DhanNow loan actions (PLAN Phase 6b/6c/7, wireframe row G):
 *  - Loan extension (G1 offer by the officer, G2/G3 Head approve or reject, cancel, resend, history). The
 *    borrower confirms with an OTP in the app and pays; the Accountant verifies the payment (G4). Every figure
 *    is the backend's quote.
 *  - Hold / dispute: raise and release (maker-checker on the server), with the hold history.
 *  - Recovery EMI request (behind the `emi-conversion` flag; an ADMIN approves on the Restructure page).
 * The server is the rule-keeper: a stale version is 409 STALE_STATE, a self-approval SOD_VIOLATION.
 */
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Badge, ErrorState, Skeleton } from "@/components/ui";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";
import { ActionButton, DeskTable, istDate } from "@/components/staff/desk-kit";
import { useCan } from "@/components/staff/pipeline/hooks";
import { paiseToINR } from "@/lib/api/applications";
import { emiApi, extensionsApi, holdsApi, type Extension } from "@/lib/api/staff-desks";
import { HOLD_LABEL, type LoanHold } from "@/lib/domain/loan";
import { formatDate, formatDateTime } from "@/lib/utils";

const REASON: Record<string, string> = {
  NOT_DUE_YET: "Not due yet",
  LOAN_CLOSED: "Loan closed",
  RESTRUCTURED: "Converted to EMI",
  ON_HOLD: "Loan on hold",
  REPAYMENT_PENDING: "A repayment is waiting for verification",
  DEBIT_IN_FLIGHT: "A NACH debit is in flight",
  EXTENSION_CAP_REACHED: "All extensions used",
  EMI_OPEN: "An EMI offer is open",
  NOT_OVERDUE: "Not overdue",
  EXTENSION_OPEN: "An extension is open",
  SETTLEMENT_OPEN: "A settlement is open",
  FEATURE_DISABLED: "Switched off",
};
const reason = (code: string | null) => (code ? REASON[code] ?? code : null);
const lower = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ");

export function ExtensionSection({ caseId }: { caseId: string }) {
  const can = useCan();
  const key = ["extension-panel", caseId];
  const q = useQuery({ queryKey: key, queryFn: () => extensionsApi.panel(caseId) });
  const p = q.data;
  const cur = p?.current ?? null;
  const open = cur && ["OFFERED", "APPROVED", "ACCEPTED", "PAYMENT_PENDING"].includes(cur.status);
  return (
    <Section title="Loan extension">
      {q.isLoading ? (
        <Skeleton variant="line" rows={2} />
      ) : q.isError || !p ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="space-y-3">
          <FieldGrid cols={4}>
            <Field label="Extensions" keyLabel>{`${p.used} of ${p.cap}`}</Field>
            <Field label="Due date">{p.dueDate ? formatDate(p.dueDate) : null}</Field>
            <Field label="Original due date">{p.originalDueDate ? formatDate(p.originalDueDate) : null}</Field>
            <Field label="If extended today" caption={p.newDueDate ? `new due ${formatDate(p.newDueDate)}` : undefined}>
              {p.quoteForToday ? paiseToINR(p.quoteForToday.totalPaise) : null}
            </Field>
          </FieldGrid>
          {p.quoteForToday && (
            <p className="text-xs text-muted">
              Fee {paiseToINR(p.quoteForToday.feePaise)} + GST {paiseToINR(p.quoteForToday.gstPaise)} + interest{" "}
              {paiseToINR(p.quoteForToday.interestPaise)}
              {p.quoteForToday.lateChargePaise > 0 && ` + late charge ${paiseToINR(p.quoteForToday.lateChargePaise)} (${p.quoteForToday.lateDays} days)`}. The late
              charge follows the bank date of the payment. Extended loans do not move up the credit ladder.
            </p>
          )}
          {cur && open && (
            <div className="rounded border border-line bg-grey-50 p-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="info">Extension {cur.seq}: {lower(cur.status)}</Badge>
                {cur.payBy && <span>pay by {formatDate(cur.payBy)}</span>}
                {cur.oldDueDate && cur.newDueDate && <span>{formatDate(cur.oldDueDate)} → {formatDate(cur.newDueDate)}</span>}
                {cur.seenByBorrowerAt && <span className="text-muted">seen {formatDateTime(cur.seenByBorrowerAt)}</span>}
              </div>
              {cur.callNote && <p className="mt-1 text-xs text-muted">Call note: {cur.callNote}</p>}
              <div className="mt-2 flex flex-wrap gap-2">
                {cur.status === "OFFERED" && can("extension:approve") && (
                  <>
                    <ActionButton label="Approve" variant="gold" confirm="The borrower will see the offer in the app and confirm it with an OTP." run={() => extensionsApi.approve(cur.id)} invalidate={key} success="Extension approved" />
                    <ActionButton
                      label="Reject"
                      fields={[{ name: "reason", label: "Reason (told to the officer)", required: true }, { name: "note", label: "Note" }]}
                      run={(v) => extensionsApi.reject(cur.id, v.reason, v.note)}
                      invalidate={key}
                      success="Extension rejected"
                    />
                  </>
                )}
                {cur.status !== "PAYMENT_PENDING" && can("extension:offer") && (
                  <ActionButton
                    label="Cancel"
                    fields={[{ name: "reason", label: "Why", required: true }]}
                    run={(v) => extensionsApi.cancel(cur.id, v.reason)}
                    invalidate={key}
                    success="Extension cancelled"
                  />
                )}
                {(cur.status === "APPROVED" || cur.status === "ACCEPTED") && can("extension:offer") && (
                  <ActionButton label="Resend to borrower" run={() => extensionsApi.resend(cur.id)} invalidate={key} success="Sent again" />
                )}
                {cur.status === "ACCEPTED" && (
                  <span className="self-center text-xs text-muted">Record the payment below as kind “Extension charge” at exactly the quote.</span>
                )}
              </div>
            </div>
          )}
          {!open && can("extension:offer") && (
            <div className="flex flex-wrap items-center gap-2">
              <ActionButton
                label="Offer an extension"
                variant="gold"
                disabled={!p.eligible && !(p.reasonCode === "EXTENSION_CAP_REACHED" && can("collections:extension-override"))}
                fields={[
                  { name: "payBy", label: `Pay by (from ${istDate()}, before the new due date)`, type: "date", required: true },
                  { name: "callNote", label: "What the borrower asked for on the call", required: true },
                  { name: "recordingId", label: "Call recording id" },
                  ...(p.reasonCode === "EXTENSION_CAP_REACHED" ? [{ name: "overrideReason", label: "Admin override reason (past the cap)", required: true }] : []),
                ]}
                run={(v) =>
                  extensionsApi.offer(caseId, {
                    payBy: v.payBy,
                    callNote: v.callNote,
                    recordingId: v.recordingId || undefined,
                    overrideReason: v.overrideReason || undefined,
                  })
                }
                invalidate={key}
                success="Extension offered; the Collection Head approves it next"
              />
              {!p.eligible && <span className="text-xs text-muted">{reason(p.reasonCode)}</span>}
            </div>
          )}
          {p.history.length > 0 && (
            <DeskTable<Extension>
              rows={p.history}
              rowKey={(e) => e.id}
              empty=""
              columns={[
                { label: "#", render: (e) => e.seq },
                { label: "Status", render: (e) => lower(e.status) },
                { label: "Due", render: (e) => (e.oldDueDate && e.newDueDate ? `${formatDate(e.oldDueDate)} → ${formatDate(e.newDueDate)}` : "—") },
                { label: "Charge", render: (e) => paiseToINR(e.feePaise + e.gstPaise + e.interestPaise) },
                { label: "Activated", render: (e) => (e.activatedAt ? formatDateTime(e.activatedAt) : "—") },
                { label: "Note", render: (e) => e.declinedReason ?? e.rejectReason ?? e.cancelReason ?? "—" },
              ]}
            />
          )}
        </div>
      )}
    </Section>
  );
}

export function HoldSection({ loanId, hold }: { loanId: number; hold: LoanHold | null | undefined }) {
  const can = useCan();
  const key = ["loan-holds", loanId];
  const q = useQuery({ queryKey: key, queryFn: () => holdsApi.history(loanId) });
  const onHold = hold != null && hold !== "NONE";
  return (
    <Section title="Hold and dispute" tone={onHold ? "error" : "neutral"}>
      <div className="space-y-2 text-sm">
        {onHold ? (
          <p className="rounded bg-error-50 px-3 py-2 text-error-700">
            {HOLD_LABEL[hold!]}: reminders, collections calls and NACH are paused; payments are still accepted.
          </p>
        ) : (
          <p className="text-muted">No hold on this loan.</p>
        )}
        {can("holds:manage") && (
          <div className="flex gap-2">
            {!onHold ? (
              <ActionButton
                label="Raise a dispute"
                fields={[{ name: "reason", label: "What the borrower disputes", required: true }]}
                run={(v) => holdsApi.raise(loanId, v.reason)}
                invalidate={["loan-holds"]}
                success="Loan put on hold"
              />
            ) : (
              <ActionButton
                label="Release the hold"
                fields={[{ name: "note", label: "How it was resolved", required: true }]}
                run={(v) => holdsApi.release(loanId, v.note)}
                invalidate={["loan-holds"]}
                success="Hold released"
              />
            )}
          </div>
        )}
        {(q.data ?? []).length > 0 && (
          <ul className="space-y-1 text-xs text-muted">
            {q.data!.map((h, i) => (
              <li key={i}>
                {formatDateTime(h.at)} · {lower(h.fromHold)} → {lower(h.toHold)}
                {h.reason ? ` · ${h.reason}` : ""}
                {h.actorRole ? ` · ${lower(h.actorRole)}` : ""}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Section>
  );
}

export function EmiSection({ loanId }: { loanId: number }) {
  const can = useCan();
  const key = ["emi-panel", loanId];
  const q = useQuery({ queryKey: key, queryFn: () => emiApi.panel(loanId), retry: false });
  if (q.isLoading || q.isError || !q.data) return null; // flag off or not overdue: nothing to show
  const p = q.data;
  return (
    <Section title="Recovery EMI">
      <div className="space-y-2 text-sm">
        <p>
          Owed today {paiseToINR(p.owedTodayPaise)}
          {p.current ? ` · request ${lower(p.current.status)}` : ""}
          {!p.eligible && p.reasonCode ? ` · ${reason(p.reasonCode)}` : ""}
        </p>
        {p.previewForToday.length > 0 && (
          <p className="text-xs text-muted">
            {p.previewForToday.map((i) => `${formatDate(i.dueDate)} ${paiseToINR(i.totalPaise)}`).join(" · ")}
          </p>
        )}
        {p.eligible && !p.current && can("extension:offer") && (
          <ActionButton
            label="Request EMI conversion"
            fields={[
              { name: "acceptBy", label: "Borrower accepts by", type: "date", required: true },
              { name: "note", label: "Note", required: true },
            ]}
            run={(v) => emiApi.request(loanId, v.acceptBy, v.note)}
            invalidate={key}
            success="EMI requested; an admin approves it next"
          />
        )}
      </div>
    </Section>
  );
}
