"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable } from "@/components/staff/desk-kit";
import { emiApi, type EmiRequest } from "@/lib/api/staff-desks";
import { formatDate } from "@/lib/utils";

const KEY = ["emi-queue"];

/**
 * Admin · Restructure (recovery EMI, PLAN Phase 6c). Collections requests a conversion on an overdue loan;
 * only an ADMIN who did not request it approves or rejects. The borrower then accepts with an OTP in the app.
 * Behind the `emi-conversion` flag, which is OFF in v1.
 */
export default function RestructurePage() {
  const can = useCan();
  const q = useQuery({ queryKey: KEY, queryFn: () => emiApi.queue("REQUESTED") });
  if (!can("emi:approve")) return <NoAccessNotice message="Only an admin approves an EMI conversion." />;
  return (
    <div className="space-y-5">
      <PageHeader title="Restructure (EMI) approvals" subtitle="EMI conversions collections asked for, waiting for an admin" />
      <DeskTable<EmiRequest>
        query={q}
        rowKey={(r) => r.id}
        empty="No EMI requests waiting."
        columns={[
          {
            label: "Loan",
            render: (r) => (
              <Link className="font-mono" href={`/staff/collections?open=${r.loanId}&tab=collections`}>
                #{r.loanId}
              </Link>
            ),
          },
          { label: "Instalments", render: (r) => r.instalmentCount },
          { label: "Borrower must accept by", render: (r) => (r.acceptBy ? formatDate(r.acceptBy) : "—") },
          { label: "Status", render: (r) => r.status },
          {
            label: "",
            render: (r) => (
              <div className="flex gap-2">
                <ActionButton
                  label="Approve"
                  variant="gold"
                  confirm="The borrower will be offered the EMI plan to accept with an OTP."
                  run={() => emiApi.approve(r.id)}
                  invalidate={KEY}
                  success="EMI approved"
                />
                <ActionButton
                  label="Reject"
                  fields={[{ name: "note", label: "Note" }]}
                  run={(v) => emiApi.reject(r.id, "OTHER", v.note)}
                  invalidate={KEY}
                  success="EMI request rejected"
                />
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
