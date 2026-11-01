"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable } from "@/components/staff/desk-kit";
import { paiseToINR, staffApi, type ApplicationView } from "@/lib/api/applications";
import { disbursalApi } from "@/lib/api/staff-desks";
import { formatDateTime } from "@/lib/utils";

const KEY = ["disbursal-confirmations"];

/** The UTR and net amount of the pending loan (the Head entered them; the Accountant checks the bank). */
function Transfer({ loanId }: { loanId: number | null }) {
  const q = useQuery({ queryKey: ["staff-loan", loanId], queryFn: () => staffApi.loan(loanId!), enabled: loanId != null });
  if (!loanId) return <>—</>;
  if (!q.data) return <span className="text-muted">…</span>;
  return (
    <span>
      <span className="font-mono font-semibold text-navy">{paiseToINR(q.data.netDisbursedPaise)}</span>
      <span className="block font-mono text-xs text-muted">UTR {q.data.disbursalTxnRef ?? "—"}</span>
    </span>
  );
}

/**
 * Accountant · Disbursal confirmation (PLAN Phase 4 / 10b R6). The Disbursement Head entered the UTR; the
 * Accountant checks the bank and confirms, which makes the loan ACTIVE with day 0 = today (IST). A wrong
 * UTR or money not credited goes back to the Head with a reason. The confirmer is never the sanctioner or
 * the disburser (the backend refuses with SOD_VIOLATION).
 */
export default function DisbursalConfirmationPage() {
  const can = useCan();
  const q = useQuery({ queryKey: KEY, queryFn: () => staffApi.listByStatus("DISBURSAL_CONFIRMATION_PENDING") });
  if (!can("loan:activate")) return <NoAccessNotice message="Accountants confirm disbursals." />;
  return (
    <div className="space-y-5">
      <PageHeader title="Disbursal confirmation" subtitle="Money sent with a UTR, waiting for you to confirm it reached the borrower" />
      <DeskTable<ApplicationView>
        query={q}
        rowKey={(a) => a.id}
        empty="Nothing waiting for confirmation."
        columns={[
          { label: "Application", render: (a) => <Link className="font-mono" href={`/staff/customers/${a.customerId}`}>#{a.id}</Link> },
          { label: "Customer", render: (a) => a.customerName ?? `#${a.customerId}` },
          { label: "Principal", render: (a) => <span className="font-mono">{paiseToINR(a.sanctionedAmountPaise ?? a.amountRequestedPaise)}</span> },
          { label: "Sent (net) · UTR", render: (a) => <Transfer loanId={a.loanId} /> },
          { label: "Released by", render: (a) => a.disbursedByName ?? "—" },
          { label: "Waiting since", render: (a) => (a.currentStageEnteredAt ? formatDateTime(a.currentStageEnteredAt) : "—") },
          {
            label: "",
            render: (a) => (
              <div className="flex gap-2">
                <ActionButton
                  label="Confirm"
                  variant="gold"
                  confirm="The money is in the borrower's account. Day 0 is today; the loan is due in 14 days at 23:59 IST."
                  run={() => disbursalApi.confirm(a.id)}
                  invalidate={KEY}
                  success={`Disbursal #${a.id} confirmed`}
                />
                <ActionButton
                  label="Return to Head"
                  fields={[{ name: "reason", label: "Why (wrong UTR, not credited…)", required: true }]}
                  run={(v) => disbursalApi.returnToHead(a.id, v.reason)}
                  invalidate={KEY}
                  success={`Disbursal #${a.id} returned`}
                />
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
