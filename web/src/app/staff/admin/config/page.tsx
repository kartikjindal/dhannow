"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable, type ActionField } from "@/components/staff/desk-kit";
import { Badge, ErrorState, Skeleton } from "@/components/ui";
import { configApi, type ConfigRow } from "@/lib/api/staff-desks";
import { formatDateTime } from "@/lib/utils";

const KEY = ["staff-config"];

const PRICING_FIELDS: ActionField[] = [
  { name: "pfRate", label: "Processing fee rate (0.10 = 10%)", type: "number" },
  { name: "gstRate", label: "GST rate on the fee (0.18 = 18%)", type: "number" },
  { name: "dailyRate", label: "Interest per day (0.01 = 1%)", type: "number" },
  { name: "tenureDays", label: "Tenure (days)", type: "number" },
  { name: "penalRate", label: "Late charge per day (0.02 = 2%)", type: "number" },
  { name: "penalCapDays", label: "Late charge cap (days)", type: "number" },
];
const PRODUCT_FIELDS: ActionField[] = [
  { name: "overdueReviewDpd", label: "Overdue review from (days past due)", type: "number" },
  { name: "offerValidityDays", label: "Offer validity (days)", type: "number" },
  { name: "extensionMax", label: "Extensions per loan", type: "number" },
  { name: "emiGapDays", label: "EMI instalment gap (days)", type: "number" },
  { name: "emiThreeFromPaise", label: "Three EMIs from (paise)", type: "number" },
];

/** Only the fields typed in; blank ones are copied from the ACTIVE version by the backend. */
const numbers = (v: Record<string, string>) =>
  Object.fromEntries(Object.entries(v).filter(([, x]) => x.trim() !== "").map(([k, x]) => [k, Number(x)]));

function cell(v: unknown): string {
  if (v == null) return "—";
  if (Array.isArray(v)) return v.map((x) => `₹${(Number(x) / 100).toLocaleString("en-IN")}`).join(", ");
  return String(v);
}

function Versions({ kind, rows, canManage }: { kind: "pricing" | "product"; rows: ConfigRow[]; canManage: boolean }) {
  const cols = kind === "pricing"
    ? ["pfRate", "gstRate", "dailyRate", "tenureDays", "interestMode", "penalRate", "penalCapDays"]
    : ["allowedAmountsPaise", "overdueReviewDpd", "offerValidityDays", "extensionMax", "emiGapDays", "emiThreeFromPaise"];
  return (
    <DeskTable<ConfigRow>
      rows={rows}
      rowKey={(r) => r.id}
      empty="No versions."
      columns={[
        { label: "Version", render: (r) => <span className="font-mono">v{r.version}</span> },
        {
          label: "Status",
          render: (r) => <Badge variant={r.status === "ACTIVE" ? "success" : r.status === "DRAFT" ? "warning" : "neutral"}>{r.status}</Badge>,
        },
        ...cols.map((c) => ({ label: c, render: (r: ConfigRow) => <span className="font-mono">{cell(r[c])}</span> })),
        { label: "Made by", render: (r) => cell(r.createdBy) },
        { label: "Since", render: (r) => (r.updatedAt || r.createdAt ? formatDateTime(String(r.updatedAt ?? r.createdAt)) : "—") },
        {
          label: "",
          render: (r) =>
            canManage && r.status === "DRAFT" ? (
              <div className="flex gap-2">
                <ActionButton
                  label="Approve"
                  variant="gold"
                  confirm="New offers will freeze this version. Loans already offered keep theirs."
                  run={() => configApi.approve(kind, r.id)}
                  invalidate={KEY}
                  success={`v${r.version} is active`}
                />
                <ActionButton label="Discard" run={() => configApi.discard(kind, r.id)} invalidate={KEY} success="Draft discarded" />
              </div>
            ) : null,
        },
      ]}
    />
  );
}

/**
 * Admin / Finance · Pricing and product config (PLAN Phase 1 config, Phase 10 screen). Versions are never
 * edited: a change is a DRAFT made by one ADMIN or FINANCE user and approved by another (maker-checker).
 * Every offer freezes the ACTIVE version it was made on, so screens, PDFs and the ledger keep agreeing.
 */
export default function ConfigPage() {
  const can = useCan();
  const q = useQuery({ queryKey: KEY, queryFn: configApi.get });
  if (!can("config:view")) return <NoAccessNotice message="Admin, Finance and Compliance see the pricing config." />;
  const manage = can("config:manage");
  return (
    <div className="space-y-6">
      <PageHeader title="Pricing and product" subtitle="Versioned terms; a change needs a second person to approve" />
      {q.isLoading ? (
        <Skeleton className="h-40 w-full" />
      ) : q.isError || !q.data ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <section className="space-y-2">
            <div className="flex items-center justify-between">
              <h2 className="mb-0 text-base">Pricing</h2>
              {manage && (
                <ActionButton label="New pricing draft" fields={PRICING_FIELDS} run={(v) => configApi.draft("pricing", numbers(v))} invalidate={KEY} success="Pricing draft saved" />
              )}
            </div>
            <Versions kind="pricing" rows={q.data.pricing} canManage={manage} />
          </section>
          <section className="space-y-2">
            <div className="flex items-center justify-between">
              <h2 className="mb-0 text-base">Product</h2>
              {manage && (
                <ActionButton label="New product draft" fields={PRODUCT_FIELDS} run={(v) => configApi.draft("product", numbers(v))} invalidate={KEY} success="Product draft saved" />
              )}
            </div>
            <Versions kind="product" rows={q.data.product} canManage={manage} />
          </section>
        </>
      )}
    </div>
  );
}
