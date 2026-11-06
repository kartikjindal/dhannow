"use client";

import * as React from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { PageHeader } from "@/components/staff/staff-ui";
import { NoAccessNotice } from "@/components/staff/pipeline/actions";
import { useCan } from "@/components/staff/pipeline/hooks";
import { ActionButton, DeskTable } from "@/components/staff/desk-kit";
import { selfieReviewsApi, type SelfieReview } from "@/lib/api/staff-desks";
import { formatDateTime } from "@/lib/utils";

const KEY = ["selfie-reviews"];

/**
 * Credit · Selfie review (PLAN Phase 8): the guided straight / right / left capture waits here for a person.
 * Approve passes the check; reject (reason required) reopens the step so the borrower retakes it. The frame
 * links are short-lived; refresh the page if one has expired.
 */
export default function SelfieReviewPage() {
  const can = useCan();
  const q = useQuery({ queryKey: KEY, queryFn: selfieReviewsApi.list });
  if (!can("selfie:review")) return <NoAccessNotice message="The credit team reviews selfies." />;
  return (
    <div className="space-y-5">
      <PageHeader title="Selfie review" subtitle="Three frames per borrower: straight, right and left" />
      <DeskTable<SelfieReview>
        query={q}
        rowKey={(r) => r.applicationId}
        empty="No selfies waiting for review."
        columns={[
          {
            label: "Borrower",
            render: (r) => (
              <span>
                {r.borrowerName ?? "—"}
                <Link className="block font-mono text-xs" href={`/staff/customers/${r.customerId}`}>
                  Application #{r.applicationId}
                </Link>
              </span>
            ),
          },
          {
            label: "Frames",
            render: (r) => (
              <div className="flex gap-2">
                {(["straight", "right", "left"] as const).map((k) =>
                  r.frames[k] ? (
                    <a key={k} href={r.frames[k]} target="_blank" rel="noreferrer" title={`${k} (opens full size)`}>
                      {/* eslint-disable-next-line @next/next/no-img-element -- short-lived presigned S3 link */}
                      <img src={r.frames[k]} alt={`Selfie, ${k}`} className="h-24 w-20 rounded object-cover" />
                    </a>
                  ) : (
                    <span key={k} className="grid h-24 w-20 place-items-center rounded bg-grey-100 text-xs text-muted">
                      {k}
                    </span>
                  ),
                )}
              </div>
            ),
          },
          { label: "Tries", render: (r) => r.attempts },
          { label: "Submitted", render: (r) => formatDateTime(r.submittedAt) },
          {
            label: "",
            render: (r) => (
              <div className="flex gap-2">
                <ActionButton label="Approve" variant="gold" run={() => selfieReviewsApi.approve(r.applicationId)} invalidate={KEY} success="Selfie approved" />
                <ActionButton
                  label="Reject"
                  fields={[{ name: "reason", label: "Reason (the borrower retakes the selfie)", required: true }]}
                  run={(v) => selfieReviewsApi.reject(r.applicationId, v.reason)}
                  invalidate={KEY}
                  success="Selfie rejected; the borrower will retake it"
                />
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
