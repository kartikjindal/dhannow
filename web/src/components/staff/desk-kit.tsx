"use client";

/**
 * Small shared pieces for the PLAN Phase 10 desks (disbursal confirmation, refunds, complaints, selfie review,
 * restructure, holidays, config, adjustment log): a query-backed table and an action button that may ask for
 * text (a reason, a UTR) in a dialog before it runs. Figures are formatted here, never computed.
 */
import * as React from "react";
import { useMutation, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { Dialog, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState, ErrorState, Skeleton, toast } from "@/components/ui";
import { errMessage } from "@/components/staff/pipeline/hooks";

export { istDate } from "@/lib/calc/loan-math";

export type DeskColumn<T> = { label: string; render: (row: T) => React.ReactNode; className?: string };

/** Rows from a `query` (loading / error / empty handled here) or given directly as `rows`. */
export function DeskTable<T>({
  query,
  rows: given,
  columns,
  rowKey,
  empty,
}: {
  query?: UseQueryResult<T[]>;
  rows?: T[];
  columns: DeskColumn<T>[];
  rowKey: (row: T) => string | number;
  empty: string;
}) {
  if (query?.isLoading) return <Skeleton className="h-40 w-full" />;
  if (query?.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  const rows = given ?? query?.data ?? [];
  if (rows.length === 0) return <EmptyState title={empty} />;
  return (
    <div className="card overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-line text-left text-muted">
            {columns.map((c) => (
              <th key={c.label} className="px-4 py-2 font-medium">{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)} className="border-b border-line/60 align-top last:border-0">
              {columns.map((c) => (
                <td key={c.label} className={c.className ?? "px-4 py-2"}>{c.render(r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export type ActionField = { name: string; label: string; type?: "text" | "date" | "number"; required?: boolean; placeholder?: string };

/**
 * A button that runs `run(values)` after an optional dialog collecting `fields` (or a plain confirm when
 * `confirm` is set). Refreshes `invalidate` and toasts the result; backend errors (SoD, stale state) show as is.
 */
export function ActionButton({
  label,
  title,
  fields = [],
  confirm,
  run,
  invalidate,
  success,
  variant = "outline",
  disabled,
}: {
  label: string;
  title?: string;
  fields?: ActionField[];
  confirm?: string;
  run: (values: Record<string, string>) => Promise<unknown>;
  invalidate: unknown[];
  success: string;
  variant?: "gold" | "outline" | "ghost";
  disabled?: boolean;
}) {
  const qc = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [values, setValues] = React.useState<Record<string, string>>({});
  const m = useMutation({
    mutationFn: () => run(values),
    onSuccess: () => {
      setOpen(false);
      setValues({});
      toast.success(success);
      void qc.invalidateQueries({ queryKey: invalidate });
    },
    onError: (e) => toast.error(errMessage(e)),
  });
  const needsDialog = fields.length > 0 || !!confirm;
  const ready = fields.every((f) => !f.required || (values[f.name] ?? "").trim() !== "");
  return (
    <>
      <button
        type="button"
        className={`btn btn-sm btn-${variant}`}
        disabled={disabled || m.isPending}
        onClick={() => (needsDialog ? setOpen(true) : m.mutate())}
      >
        {m.isPending && !open ? <Loader2 size={13} className="animate-spin" /> : null}
        {label}
      </button>
      {needsDialog && (
        <Dialog open={open} onClose={() => !m.isPending && setOpen(false)} size="md" aria-label={title ?? label}>
          <DialogHeader>
            <DialogTitle>{title ?? label}</DialogTitle>
            {confirm && <p className="text-sm text-muted">{confirm}</p>}
          </DialogHeader>
          <div className="space-y-3">
            {fields.map((f) => (
              <label key={f.name} className="block text-sm">
                <span className="mb-1 block text-muted">
                  {f.label}
                  {f.required ? " *" : ""}
                </span>
                <input
                  className="field w-full"
                  type={f.type ?? "text"}
                  placeholder={f.placeholder}
                  value={values[f.name] ?? ""}
                  onChange={(e) => setValues((v) => ({ ...v, [f.name]: e.target.value }))}
                />
              </label>
            ))}
          </div>
          <DialogFooter>
            <button type="button" className="btn btn-outline" onClick={() => setOpen(false)} disabled={m.isPending}>
              Cancel
            </button>
            <button type="button" className="btn btn-gold" disabled={!ready || m.isPending} onClick={() => m.mutate()}>
              {m.isPending ? <Loader2 size={14} className="animate-spin" /> : null} {label}
            </button>
          </DialogFooter>
        </Dialog>
      )}
    </>
  );
}


