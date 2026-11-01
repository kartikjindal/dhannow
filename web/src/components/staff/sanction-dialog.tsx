"use client";

/**
 * "Accept lead" — the Credit Executive's FINAL credit decision (revamp.md Phase 2, decision 27).
 *
 * There is no Head counter-approval behind this: whatever is submitted here is the sanction the
 * borrower is offered and held to. The amount is a credit-ladder chip within the customer's level, and the
 * figures are the backend's quote on the ACTIVE pricing (PLAN Phase 10b) — the console computes no money.
 * The release then goes to the Disbursement Head and the Accountant's confirmation starts day 0.
 *
 * The bank account + IFSC are shown read-only: credit verifies them, but only the borrower may
 * change where the money lands (Phase 3's disbursal-account step, which penny-drops a change).
 */

import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input, Skeleton, toast } from "@/components/ui";
import { paiseToINR, staffApi, type ApplicationView } from "@/lib/api/applications";
import { creditLevelApi } from "@/lib/api/staff-desks";
import { useRefreshAfterAction, errMessage } from "@/components/staff/pipeline/hooks";

export function SanctionDialog({
  app,
  open,
  onClose,
}: {
  app: ApplicationView;
  open: boolean;
  onClose: () => void;
}) {
  const refresh = useRefreshAfterAction();
  const [amountPaise, setAmountPaise] = React.useState<number | null>(null);
  const [remarks, setRemarks] = React.useState("");

  // The salary account credit verified at intake — read-only context for the decision.
  const profile = useQuery({
    queryKey: ["staff-profile", app.id],
    queryFn: () => staffApi.getProfile(app.id),
    enabled: open,
  });
  // The customer's ladder level and every ladder amount priced by the backend.
  const level = useQuery({
    queryKey: ["credit-level", app.customerId],
    queryFn: () => creditLevelApi.get(app.customerId),
    enabled: open,
  });
  const chips = (level.data?.quotes ?? []).filter((q) => q.principalPaise <= (level.data?.current.maxPaise ?? 0));

  const m = useMutation({
    mutationFn: () => staffApi.sanction(app.id, { sanctionedAmountPaise: amountPaise!, remarks: remarks.trim() || undefined }),
    onSuccess: () => {
      refresh(app.id);
      toast.success(`Application #${app.id} sanctioned`);
      onClose();
    },
  });

  const preview = chips.find((q) => q.principalPaise === amountPaise) ?? null;

  return (
    // !max-w / !w: globals.css's un-layered `.modal { max-width: 460px }` outranks plain utilities.
    // NB the app's root font-size is 12px, so 52rem here is 624px — not the 832px a 16px root
    // would give. Sized to hold the two-column amount/date row and the schedule grid without wrap.
    <Dialog open={open} onClose={onClose} className="!max-w-[52rem] !w-[min(52rem,94vw)]">
      <DialogHeader>
        <DialogTitle>Accept lead — sanction application #{app.id}</DialogTitle>
        <p className="text-sm text-muted">
          This decision is final and goes straight to disbursement. There is no second approval.
        </p>
      </DialogHeader>

      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
          Amount{level.data ? ` · level ${level.data.current.level}, up to ${paiseToINR(level.data.current.maxPaise)}` : ""}
        </p>
        {level.isLoading ? (
          <Skeleton variant="line" rows={1} />
        ) : level.isError ? (
          <p className="text-sm text-error-700">{errMessage(level.error)}</p>
        ) : (
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Sanctioned amount">
            {chips.map((q) => (
              <button
                key={q.principalPaise}
                type="button"
                role="radio"
                aria-checked={amountPaise === q.principalPaise}
                className={`btn btn-sm ${amountPaise === q.principalPaise ? "btn-gold" : "btn-outline"}`}
                onClick={() => setAmountPaise(q.principalPaise)}
              >
                {paiseToINR(q.principalPaise)}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="mt-4 rounded border border-line bg-ivory p-4">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Salary account</p>
        {profile.isLoading ? (
          <Skeleton variant="line" rows={2} />
        ) : (
          <dl className="grid gap-x-6 gap-y-1 text-[10.4px] sm:grid-cols-3">
            <Kv label="Bank" value={profile.data?.salaryBank} />
            <Kv label="Account number" value={profile.data?.salaryAccountNumber} />
            <Kv label="IFSC" value={profile.data?.salaryIfsc} />
          </dl>
        )}
      </div>

      {preview && (
        <div className="mt-4 rounded border border-line p-4">
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">What the borrower gets</p>
          <dl className="grid gap-x-6 gap-y-1 text-[10.4px] sm:grid-cols-2">
            <Kv label="Principal" value={paiseToINR(preview.principalPaise)} />
            <Kv label="Processing fee" value={paiseToINR(preview.feePaise)} />
            <Kv label="GST on fee" value={paiseToINR(preview.gstPaise)} />
            <Kv label={`Interest (${preview.tenureDays} days, upfront)`} value={paiseToINR(preview.interestPaise)} />
            <Kv label="Borrower receives" value={paiseToINR(preview.netPaise)} emphasis />
            <Kv label="Repays (principal)" value={paiseToINR(preview.repayPaise)} emphasis />
          </dl>
          <p className="mt-2 text-xs text-muted">
            Due {preview.tenureDays} days after the confirmed disbursal, 23:59 IST. Release goes to the Disbursement Head,
            then the Accountant confirms it.
          </p>
        </div>
      )}

      <div className="mt-4">
        <Input
          label="Remarks"
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
          placeholder="What you verified, and anything the next person should know"
          helperText="Staff-only — never shown to the borrower."
        />
      </div>

      {m.error ? <p className="mt-2 text-sm text-error-700">{errMessage(m.error)}</p> : null}

      <DialogFooter>
        <button onClick={onClose} className="btn btn-outline" disabled={m.isPending}>
          Cancel
        </button>
        <button
          onClick={() => m.mutate()}
          disabled={m.isPending || !preview}
          className="btn btn-gold disabled:opacity-50"
        >
          {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} Sanction
        </button>
      </DialogFooter>
    </Dialog>
  );
}

function Kv({ label, value, emphasis }: { label: string; value?: string | null; emphasis?: boolean }) {
  return (
    <div className="flex justify-between gap-4 sm:block">
      <dt className="text-muted">{label}</dt>
      <dd className={emphasis ? "font-semibold text-navy" : "text-ink"}>{value || "—"}</dd>
    </div>
  );
}
