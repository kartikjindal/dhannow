"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan, useStaffMe } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable } from "@/components/staff/desk-kit";
import { Badge } from "@/components/ui";
import { complaintsApi, COMPLAINT_CATEGORIES, type Complaint } from "@/lib/api/staff-desks";
import { formatDateTime } from "@/lib/utils";

const STATUSES = ["OPEN", "ACKNOWLEDGED", "IN_REVIEW", "ESCALATED", "RESOLVED"] as const;
const label = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ");

/** The next SLA milestone and whether it has passed (ack 2 working days, review 5, resolve 15 calendar days). */
function Sla({ c }: { c: Complaint }) {
  const next =
    c.status === "OPEN" ? ["Acknowledge by", c.ackDueAt] : c.status === "ACKNOWLEDGED" ? ["Review by", c.reviewDueAt] : c.status === "RESOLVED" ? null : ["Resolve by", c.resolveDueAt];
  return (
    <span>
      {next ? `${next[0]} ${formatDateTime(next[1] as string)}` : c.resolvedAt ? `Resolved ${formatDateTime(c.resolvedAt)}` : "—"}
      {c.breached.length > 0 && <Badge variant="error" className="ml-1">SLA missed: {c.breached.map(label).join(", ")}</Badge>}
    </span>
  );
}

/**
 * Support · Complaints (PLAN Phase 7 / 10): the ticket queue with its SLA dates. SUPPORT and ADMIN act;
 * COMPLIANCE reads. A move the status does not allow, or a stale version, is 409 STALE_STATE from the backend.
 */
export default function ComplaintsPage() {
  const can = useCan();
  const me = useStaffMe().data;
  const [status, setStatus] = React.useState("");
  const [category, setCategory] = React.useState("");
  const [breached, setBreached] = React.useState(false);
  const key = ["complaints", status, category, breached];
  const q = useQuery({
    queryKey: key,
    queryFn: () => complaintsApi.list({ status: status || undefined, category: category || undefined, breached: breached || undefined }),
  });
  if (!can("complaints:view")) return <NoAccessNotice message="Support, Compliance and Admin see complaints." />;
  const work = can("complaints:work");
  return (
    <div className="space-y-5">
      <PageHeader title="Complaints" subtitle="Grievance tickets with their reply-by dates">
        {work && (
          <ActionButton
            label="File for a caller"
            variant="gold"
            fields={[
              { name: "customerId", label: "Customer number", type: "number", required: true },
              { name: "category", label: `Category (${COMPLAINT_CATEGORIES.join(", ")})`, required: true, placeholder: "PAYMENT" },
              { name: "body", label: "What the customer said", required: true },
              { name: "loanId", label: "Loan number (if about a loan)", type: "number" },
            ]}
            run={(v) =>
              complaintsApi.file({
                customerId: Number(v.customerId),
                category: v.category.trim().toUpperCase(),
                body: v.body,
                loanId: v.loanId ? Number(v.loanId) : undefined,
              })
            }
            invalidate={["complaints"]}
            success="Complaint filed; the customer is told the ticket number"
          />
        )}
      </PageHeader>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <select className="field" value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
          <option value="">All statuses</option>
          {STATUSES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <select className="field" value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category">
          <option value="">All categories</option>
          {COMPLAINT_CATEGORIES.map((c) => <option key={c} value={c}>{label(c)}</option>)}
        </select>
        <label className="flex items-center gap-1">
          <input type="checkbox" checked={breached} onChange={(e) => setBreached(e.target.checked)} /> SLA missed only
        </label>
      </div>
      <DeskTable<Complaint>
        query={q}
        rowKey={(c) => c.id}
        empty="No complaints match."
        columns={[
          { label: "Ticket", render: (c) => <span className="font-mono">{c.ticketNo}</span> },
          { label: "Customer", render: (c) => <Link href={`/staff/customers/${c.customerId}`}>#{c.customerId}</Link> },
          { label: "Loan", render: (c) => (c.loanId ? `#${c.loanId}` : "—") },
          { label: "Category", render: (c) => label(c.category) },
          { label: "Assigned", render: (c) => (c.assignedTo ? `#${c.assignedTo}` : "—") },
          { label: "Complaint", render: (c) => <span className="line-clamp-3 max-w-xs">{c.body}</span> },
          { label: "Status", render: (c) => <Badge variant={c.status === "RESOLVED" ? "success" : c.status === "ESCALATED" ? "error" : "info"}>{label(c.status)}</Badge> },
          { label: "Filed", render: (c) => formatDateTime(c.createdAt) },
          { label: "SLA", render: (c) => <Sla c={c} /> },
          {
            label: "",
            render: (c) =>
              !work || c.status === "RESOLVED" ? null : (
                <div className="flex flex-wrap gap-1">
                  {me && c.assignedTo !== Number(me.id) && (
                    <ActionButton label="Assign to me" run={() => complaintsApi.assign(c.id, Number(me.id))} invalidate={["complaints"]} success={`${c.ticketNo} is yours`} />
                  )}
                  {c.status === "OPEN" && (
                    <ActionButton label="Acknowledge" run={() => complaintsApi.acknowledge(c.id, c.version)} invalidate={["complaints"]} success={`${c.ticketNo} acknowledged`} />
                  )}
                  {c.status === "ACKNOWLEDGED" && (
                    <ActionButton label="Start review" run={() => complaintsApi.review(c.id, c.version)} invalidate={["complaints"]} success={`${c.ticketNo} in review`} />
                  )}
                  <ActionButton
                    label="Resolve"
                    variant="gold"
                    fields={[{ name: "note", label: "Resolution (shown to the borrower)", required: true }]}
                    run={(v) => complaintsApi.resolve(c.id, c.version, v.note)}
                    invalidate={["complaints"]}
                    success={`${c.ticketNo} resolved`}
                  />
                  {c.status !== "ESCALATED" && (
                    <ActionButton
                      label="Escalate"
                      fields={[{ name: "note", label: "Why", required: true }]}
                      run={(v) => complaintsApi.escalate(c.id, c.version, v.note)}
                      invalidate={["complaints"]}
                      success={`${c.ticketNo} escalated`}
                    />
                  )}
                </div>
              ),
          },
        ]}
      />
    </div>
  );
}
