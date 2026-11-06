"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable } from "@/components/staff/desk-kit";
import { holidaysApi, type Holiday } from "@/lib/api/staff-desks";
import { formatDate } from "@/lib/utils";

const KEY = ["holidays"];

/**
 * Admin · Holiday calendar (PLAN Phase 7, CC-64). Complaint SLAs skip these days and weekends. Loan due
 * dates never move for a holiday.
 */
export default function HolidaysPage() {
  const can = useCan();
  const q = useQuery({ queryKey: KEY, queryFn: holidaysApi.list });
  if (!can("holidays:manage")) return <NoAccessNotice message="Admin access only." />;
  return (
    <div className="space-y-5">
      <PageHeader title="Holiday calendar" subtitle="Non-working days for complaint SLAs (loan due dates never move)">
        <ActionButton
          label="Add holiday"
          variant="gold"
          fields={[
            { name: "day", label: "Date", type: "date", required: true },
            { name: "name", label: "Name", required: true, placeholder: "e.g. Diwali" },
          ]}
          run={(v) => holidaysApi.add(v.day, v.name)}
          invalidate={KEY}
          success="Holiday added"
        />
      </PageHeader>
      <DeskTable<Holiday>
        query={q}
        rowKey={(h) => h.day}
        empty="No holidays yet."
        columns={[
          { label: "Date", render: (h) => formatDate(h.day) },
          { label: "Name", render: (h) => h.name },
          {
            label: "",
            render: (h) => (
              <ActionButton label="Remove" confirm={`Remove ${h.name}?`} run={() => holidaysApi.remove(h.day)} invalidate={KEY} success="Holiday removed" />
            ),
          },
        ]}
      />
    </div>
  );
}
