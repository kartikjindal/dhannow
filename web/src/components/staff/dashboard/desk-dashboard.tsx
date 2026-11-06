"use client";

/**
 * Dashboard for the PLAN Phase 7 desk roles (SUPPORT, FINANCE, COMPLIANCE, RECOVERY): one tile per desk the role
 * works, each a live count from the desk's own endpoint and a link to it. They have no navix role view.
 */
import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { paiseToINR } from "@/lib/api/applications";
import { adjustmentsApi, complaintsApi, configApi, refundCasesApi } from "@/lib/api/staff-desks";
import type { StaffRole } from "@/lib/auth/rbac";
import { istDate } from "@/lib/calc/loan-math";

type Tile = { key: string; title: string; href: string; load: () => Promise<{ value: string; caption: string }> };

const TILES: Record<string, Tile> = {
  complaintsOpen: {
    key: "complaintsOpen",
    title: "Open complaints",
    href: "/staff/support/complaints",
    load: async () => {
      const rows = (await complaintsApi.list()).filter((c) => c.status !== "RESOLVED");
      return { value: String(rows.length), caption: `${rows.filter((c) => c.breached.length > 0).length} past an SLA date` };
    },
  },
  refundsOpen: {
    key: "refundsOpen",
    title: "Refunds owed",
    href: "/staff/collections/refunds",
    load: async () => {
      const rows = await refundCasesApi.list("OPEN");
      return { value: paiseToINR(rows.reduce((s, r) => s + r.amountPaise, 0)), caption: `${rows.length} open cases` };
    },
  },
  configDrafts: {
    key: "configDrafts",
    title: "Pricing drafts to approve",
    href: "/staff/admin/config",
    load: async () => {
      const c = await configApi.get();
      const n = [...c.pricing, ...c.product].filter((r) => r.status === "DRAFT").length;
      return { value: String(n), caption: n ? "A second person approves each draft" : "Nothing waiting" };
    },
  },
  adjustmentsToday: {
    key: "adjustmentsToday",
    title: "Balance adjustments today",
    href: "/staff/compliance/adjustments",
    load: async () => {
      const today = istDate();
      const rows = (await adjustmentsApi.list()).filter((a) => istDate(a.at) === today);
      return { value: String(rows.length), caption: "Extensions, EMI, reversals, settlements, refunds" };
    },
  },
};

const BY_ROLE: Partial<Record<StaffRole, string[]>> = {
  SUPPORT: ["complaintsOpen"],
  FINANCE: ["refundsOpen", "configDrafts", "adjustmentsToday"],
  COMPLIANCE: ["complaintsOpen", "refundsOpen", "adjustmentsToday", "configDrafts"],
};

function DeskTile({ tile }: { tile: Tile }) {
  const q = useQuery({ queryKey: ["desk-tile", tile.key], queryFn: tile.load, refetchInterval: 60_000 });
  return (
    <Link href={tile.href} className="block rounded-[22px] border border-line bg-surface p-5 shadow-sm hover:shadow" style={{ textDecoration: "none" }}>
      <div className="text-xs font-semibold uppercase tracking-wide text-muted">{tile.title}</div>
      <div className="font-display text-4xl text-ink">{q.isLoading ? "…" : q.isError ? "—" : q.data?.value}</div>
      <div className="text-xs text-muted">{q.isError ? "Could not load" : q.data?.caption}</div>
    </Link>
  );
}

export function DeskDashboard({ role }: { role: StaffRole }) {
  const keys = BY_ROLE[role] ?? [];
  if (keys.length === 0) {
    return (
      <p className="rounded border border-line bg-white p-4 text-sm text-muted">
        Your role has no desks of its own yet. Customers are in the menu on the left.
      </p>
    );
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {keys.map((k) => (
        <DeskTile key={k} tile={TILES[k]} />
      ))}
    </div>
  );
}
