"use client";

import * as React from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Loader2, RefreshCw, Pencil, Ban, Trash2, AlertTriangle, Gauge, Send, IndianRupee, ShieldOff } from "lucide-react";
import { ErrorState, Input, Select, Skeleton, toast } from "@/components/ui";
import { PillTabs } from "@/components/ui/pill-tabs";
import { PageHeader } from "@/components/staff/staff-ui";
import {
  PermissionGate,
  NoAccessNotice,
  errMessage,
  AdminForceDisbursementAction,
  SanctionedRejectAction,
} from "@/components/staff/live-pipeline";
import { CUSTOMER_TABS, CustomerTabBody } from "@/components/staff/customer-tabs";
import { CustomerExportMenu } from "@/components/staff/customer-export-menu";
import { ApplicationDetailDialog } from "@/components/staff/application-detail-dialog";
import { CreditScoreGauge } from "@/components/staff/credit-score-gauge";
import { LimitBasisBadge } from "@/components/staff/detail-parts";
import {
  customersApi,
  adminApi,
  rupeesToPaise,
  paiseToINR,
  type CustomerDetail,
  type BlocklistType,
} from "@/lib/api/applications";
import { CreditLevelCard } from "@/components/staff/credit-level-card";
import { useCan } from "@/components/staff/pipeline/hooks";
import { rupeeInputPreview } from "@/lib/customers/customer-360";

/** Loan statuses that mean the loan is still live (vs. a past/closed loan). */
const OPEN_LOAN = new Set(["ACTIVE", "OVERDUE", "IN_COLLECTIONS", "DISBURSED", "DEFAULTED"]);

export default function CustomerDetailPage() {
  const canManage = useCan()("customer:manage");
  const { customerId } = useParams<{ customerId: string }>();
  const id = Number(customerId);
  const qc = useQueryClient();
  const [tab, setTab] = React.useState("customer");
  /** An application opened from the Loan applications tab — carries its own Journey. */
  const [selectedAppId, setSelectedAppId] = React.useState<number | null>(null);
  const q = useQuery({ queryKey: ["customer", id], queryFn: () => customersApi.get(id), enabled: Number.isFinite(id) });
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["customer", id] });
    qc.invalidateQueries({ queryKey: ["customer-detail", id] });
    // The customers register reads ["customers-page", …] / ["customers-summary", …];
    // React Query prefix-matches element-by-element, so a bare ["customers"] matches neither.
    qc.invalidateQueries({ queryKey: ["customers-page"] });
    qc.invalidateQueries({ queryKey: ["customers-summary"] });
  };

  const c = q.data;
  const currentLoan = c?.loans.find((l) => OPEN_LOAN.has(l.status)) ?? null;
  const sanctionedApp = c?.applications.find((a) => a.status === "SANCTIONED") ?? null;

  return (
    <div>
      <Link href="/staff/customers" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-navy">
        <ArrowLeft size={15} /> All customers
      </Link>
      <PageHeader
        title={c?.profile?.fullName ?? `Customer #${id}`}
        subtitle={`Customer #${id} · borrower history${c?.ownerName ? ` · owner ${c.ownerName}` : " · Unallocated"}`}
      >
        <button onClick={() => q.refetch()} className="flex items-center gap-1.5 rounded border border-line px-3 py-1.5 text-xs text-muted hover:bg-grey-100 hover:text-ink">
          {q.isFetching ? <Loader2 size={13} className="animate-spin" /> : <RefreshCw size={13} />} Refresh
        </button>
        {/* ADMIN-only full-profile export (CSV / PDF) — renders nothing for every other working role. */}
        <CustomerExportMenu customerId={id} detail={c} />
      </PageHeader>

      <PermissionGate permission="customer:view" fallback={<NoAccessNotice />}>
        {q.isLoading ? (
          <div className="rounded border border-line bg-white p-4 shadow-sm">
            <Skeleton variant="line" rows={8} />
          </div>
        ) : q.error ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : !c ? (
          <p className="text-sm text-error-700">Customer not found.</p>
        ) : (
          <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,340px)]">
            <div className="min-w-0 rounded border border-line bg-white p-4 shadow-sm">
              <div className="flex flex-col sm:flex-row max-h-[68vh] gap-3">
              <PillTabs tabs={CUSTOMER_TABS} active={tab} onChange={setTab} />
              <div className="min-w-0 flex-1 overflow-y-auto text-[10.4px]">
                <CustomerTabBody
                  tab={tab}
                  detail={c}
                  customerId={id}
                  app={c.applications[0] ?? null}
                  onTabChange={setTab}
                  onChanged={invalidate}
                  onOpenApplication={setSelectedAppId}
                />
              </div>
              </div>
            </div>
            <div className="space-y-6">
              {/* At-a-glance dial so the score is visible without opening the Credit Report tab. */}
              <Card title="Credit score" icon={<Gauge size={16} />}>
                <CreditScoreGauge
                  score={c.profile?.creditScore ?? c.creditBrief?.creditScore ?? null}
                  starRating={c.profile?.starRating ?? c.creditBrief?.starRating ?? null}
                  recommendation={c.profile?.recommendation ?? c.creditBrief?.recommendation ?? null}
                  state={c.creditBrief?.bureauState ?? "NOT_FETCHED"}
                  size="sm"
                />
              </Card>
              <CreditLevelCard customerId={id} canSetException={canManage} />
              <PermissionGate permission="customer:manage">
                {sanctionedApp && (
                  <Card title="Force to disbursement (admin)" icon={<Send size={16} />}>
                    <div className="flex flex-wrap items-start gap-2">
                      <AdminForceDisbursementAction app={sanctionedApp} />
                      <SanctionedRejectAction app={sanctionedApp} />
                    </div>
                  </Card>
                )}
                {sanctionedApp && (
                  <SanctionedAmountCard
                    customerId={id}
                    app={sanctionedApp}
                    overridePaise={c.limitOverridePaise ?? null}
                    onSaved={invalidate}
                  />
                )}
                <AdminEditCard detail={c} onSaved={invalidate} />
                {c.blocked && <UnblockCard customerId={id} reason={c.blockedReason ?? null} onDone={invalidate} />}
                <BlocklistCard customerId={id} />
                <DeleteCustomerCard
                  customerId={id}
                  name={c.profile?.fullName ?? `Customer #${id}`}
                  hasLiveLoan={!!currentLoan}
                />
              </PermissionGate>
            </div>
          </div>
        )}
      </PermissionGate>

      {/* Opened from the Loan applications tab — the only route to an older application's Journey,
          since queues are status-filtered and a closed reborrow predecessor appears in none. */}
      {selectedAppId != null && (
        <ApplicationDetailDialog
          applicationId={selectedAppId}
          initialTab="customer"
          onClose={() => setSelectedAppId(null)}
        />
      )}
    </div>
  );
}

function Card({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded border border-line bg-white p-5 shadow-sm">
      <div className="mb-3 flex items-center gap-2 font-serif text-base font-semibold text-navy">{icon} {title}</div>
      {children}
    </div>
  );
}

function DeleteCustomerCard({ customerId, name, hasLiveLoan }: { customerId: number; name: string; hasLiveLoan: boolean }) {
  const router = useRouter();
  const [arming, setArming] = React.useState(false);
  const [typed, setTyped] = React.useState("");
  const m = useMutation({
    mutationFn: () => customersApi.remove(customerId),
    onSuccess: () => {
      toast.success("Customer deleted");
      router.push("/staff/customers");
    },
  });
  const confirmed = typed.trim() === name.trim();

  return (
    <div className="rounded border border-error-200 bg-error-50 p-5 shadow-sm">
      <div className="mb-2 flex items-center gap-2 font-serif text-base font-semibold text-error-700">
        <AlertTriangle size={16} /> Danger zone
      </div>
      <p className="mb-3 text-xs text-error-700">
        Permanently delete this customer and <strong>all</strong> of their data — applications, loans,
        payments, verifications, documents and collections. This cannot be undone.
      </p>
      {hasLiveLoan ? (
        <p className="mb-3 rounded border border-error-200 bg-white/60 px-2 py-1 text-xs font-semibold text-error-700">
          ⚠ This customer has a live loan — deleting also erases that active loan record.
        </p>
      ) : null}

      {!arming ? (
        <button
          onClick={() => { setArming(true); setTyped(""); m.reset(); }}
          className="flex items-center gap-1.5 rounded border border-error-300 bg-white px-3 py-1.5 text-xs font-semibold text-error-700 hover:bg-error-100"
        >
          <Trash2 size={13} /> Delete this user
        </button>
      ) : (
        <div className="space-y-2">
          <label className="block text-xs text-error-700">
            Type <span className="font-mono font-semibold">{name}</span> to confirm:
          </label>
          <Input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={name} autoFocus />
          <div className="flex items-center gap-2">
            <button
              onClick={() => m.mutate()}
              disabled={!confirmed || m.isPending}
              className="flex items-center gap-1.5 rounded bg-error-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-error-700 disabled:opacity-50"
            >
              {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Trash2 size={13} />}
              Permanently delete
            </button>
            <button
              onClick={() => { setArming(false); setTyped(""); }}
              className="rounded border border-line bg-white px-3 py-1.5 text-xs text-muted hover:bg-grey-100"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
      {m.error ? <p className="mt-2 text-xs text-error-700">{errMessage(m.error)}</p> : null}
    </div>
  );
}

function AdminEditCard({ detail, onSaved }: { detail: CustomerDetail; onSaved: () => void }) {
  const p = detail.profile;
  const [fullName, setFullName] = React.useState(p?.fullName ?? "");
  const [employer, setEmployer] = React.useState(p?.employer ?? "");
  const [employmentStatus, setEmploymentStatus] = React.useState(p?.employmentStatus ?? "");
  const [salary, setSalary] = React.useState(p?.monthlySalaryPaise != null ? String(Math.round(p.monthlySalaryPaise / 100)) : "");
  const [annualSalary, setAnnualSalary] = React.useState(p?.annualSalaryPaise != null ? String(Math.round(p.annualSalaryPaise / 100)) : "");
  const [salaryPct, setSalaryPct] = React.useState(p?.salaryPercentage != null ? String(p.salaryPercentage) : "");
  const [incrementPct, setIncrementPct] = React.useState(p?.incrementPercentage != null ? String(p.incrementPercentage) : "");
  const [salaryBank, setSalaryBank] = React.useState(p?.salaryBank ?? "");
  const [address, setAddress] = React.useState(p?.address ?? "");

  const m = useMutation({
    mutationFn: () =>
      customersApi.updateProfile(detail.customerId, {
        fullName: fullName.trim() || null,
        employer: employer.trim() || null,
        employmentStatus: employmentStatus.trim() || null,
        monthlySalaryPaise: salary ? rupeesToPaise(Number(salary.replace(/[^\d.]/g, ""))) : null,
        annualSalaryPaise: annualSalary ? rupeesToPaise(Number(annualSalary.replace(/[^\d.]/g, ""))) : null,
        salaryPercentage: salaryPct ? Number(salaryPct) : null,
        incrementPercentage: incrementPct ? Number(incrementPct) : null,
        salaryBank: salaryBank.trim() || null,
        address: address.trim() || null,
      }),
    onSuccess: () => {
      onSaved();
      toast.success("Customer details saved");
    },
  });

  return (
    <Card title="Edit KYC / salary (admin)" icon={<Pencil size={16} />}>
      <p className="mb-3 text-xs text-muted">Identity (PAN/Aadhaar) and the mobile number are locked (CC-24). Edits are audited.</p>
      <Input label="Full name" value={fullName} onChange={(e) => setFullName(e.target.value)} className="!mb-2" />
      <Input label="Employer" value={employer} onChange={(e) => setEmployer(e.target.value)} className="!mb-2" />
      <Input label="Employment status" value={employmentStatus} onChange={(e) => setEmploymentStatus(e.target.value)} className="!mb-2" />
      {/* ₹ prefix + a grouped preview under each salary field — display only; the raw digits are
          what is submitted, exactly as before. */}
      <Input
        label="Monthly salary (₹)"
        inputMode="numeric"
        value={salary}
        onChange={(e) => setSalary(e.target.value.replace(/[^\d]/g, ""))}
        leftIcon={<span aria-hidden="true">₹</span>}
        helperText={rupeeInputPreview(salary) ?? undefined}
        className="!mb-2"
      />
      <Input
        label="Annual salary (₹)"
        inputMode="numeric"
        value={annualSalary}
        onChange={(e) => setAnnualSalary(e.target.value.replace(/[^\d]/g, ""))}
        leftIcon={<span aria-hidden="true">₹</span>}
        helperText={rupeeInputPreview(annualSalary) ?? undefined}
        className="!mb-2"
      />
      <Input label="Salary percentage (%)" inputMode="decimal" value={salaryPct} onChange={(e) => setSalaryPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Increment percentage (%)" inputMode="decimal" value={incrementPct} onChange={(e) => setIncrementPct(e.target.value.replace(/[^\d.]/g, ""))} className="!mb-2" />
      <Input label="Salary bank" value={salaryBank} onChange={(e) => setSalaryBank(e.target.value)} className="!mb-2" />
      <Input label="Address" value={address} onChange={(e) => setAddress(e.target.value)} className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save changes
      </button>
    </Card>
  );
}



/**
 * ADMIN correcting the amount credit approved (sanctioned) for the customer's current, not-yet-
 * disbursed application. Saves immediately — the backend emails the customer the revised approved
 * amount. Only shown while an application is still SANCTIONED (mirrors
 * {@code AdminForceDisbursementAction}'s own visibility rule on this page); once disbursed the
 * loan's principal is fixed and cannot be corrected here.
 */
function SanctionedAmountCard({
  customerId,
  app,
  overridePaise,
  onSaved,
}: {
  customerId: number;
  app: { id: number; sanctionedAmountPaise?: number | null; eligibleLimitPaise?: number | null };
  /** The customer's ADMIN limit override, if any — decides which basis badge the limit carries. */
  overridePaise: number | null;
  onSaved: () => void;
}) {
  const currentPaise = app.sanctionedAmountPaise ?? null;
  const eligibleLimitPaise = overridePaise ?? app.eligibleLimitPaise ?? null;
  const [amount, setAmount] = React.useState(currentPaise != null ? String(Math.round(currentPaise / 100)) : "");
  const newAmountPaise = amount ? rupeesToPaise(Number(amount.replace(/[^\d]/g, ""))) : 0;

  const save = useMutation({
    mutationFn: () => customersApi.changeSanctionedAmount(customerId, app.id, newAmountPaise),
    onSuccess: () => {
      onSaved();
      toast.success("Approved amount updated");
    },
  });

  return (
    <Card title="Correct approved amount (admin)" icon={<IndianRupee size={16} />}>
      <p className="mb-3 text-xs text-muted">
        Currently approved: <span className="font-mono text-ink">{currentPaise != null ? `₹${(currentPaise / 100).toLocaleString("en-IN")}` : "—"}</span>.
        Saves immediately and emails the customer the revised approved amount. Only available before disbursement.
      </p>
      {/* Context for the correction, not a cap: the backend checks only the ₹1,000 floor and the
          amount the borrower already chose to draw. Left out when no limit is on file, so a basis
          badge never labels a figure the payload does not carry. */}
      {eligibleLimitPaise != null && (
        <p className="-mt-1.5 mb-3 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          Eligible limit:
          <span className="font-mono text-ink">{paiseToINR(eligibleLimitPaise)}</span>
          <LimitBasisBadge overridePaise={overridePaise} />
        </p>
      )}
      <Input
        label="New approved amount (₹)"
        inputMode="numeric"
        value={amount}
        onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ""))}
        placeholder={currentPaise != null ? String(Math.round(currentPaise / 100)) : "50000"}
        className="!mb-2"
      />
      {save.error && <p className="mb-2 text-sm text-error-700">{errMessage(save.error)}</p>}
      <button
        onClick={() => save.mutate()}
        disabled={save.isPending || newAmountPaise <= 0}
        className="btn btn-sm btn-navy btn-block disabled:opacity-50"
      >
        {save.isPending ? <Loader2 size={13} className="animate-spin" /> : null} Save
      </button>
    </Card>
  );
}


/**
 * ADMIN lifts a staff block (set at repayment verify, or by the accountant/admin elsewhere). The
 * block itself is a rejection-register row keyed on the mobile, so this is the customer's way back
 * in — not a blocklist edit.
 */
function UnblockCard({ customerId, reason, onDone }: { customerId: number; reason: string | null; onDone: () => void }) {
  const m = useMutation({
    mutationFn: () => customersApi.unblock(customerId),
    onSuccess: () => {
      toast.success("Customer unblocked — they may apply again");
      onDone();
    },
  });
  return (
    <Card title="Blocked for future loans" icon={<ShieldOff size={16} />}>
      <p className="mb-3 text-xs text-muted">
        {reason ? <>Reason on file: <span className="font-semibold text-ink">{reason}</span>. </> : null}
        The borrower cannot start a new application or reborrow while this holds. Unblocking also lifts the
        mirrored blocklist entries this block added.
      </p>
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending} className="btn btn-sm btn-navy btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <ShieldOff size={13} />} Unblock customer
      </button>
    </Card>
  );
}

const BLOCKLIST_TYPES: BlocklistType[] = ["PAN", "AADHAAR_REF", "PHONE", "DEVICE", "BANK_ACCOUNT"];

function BlocklistCard({ customerId }: { customerId: number }) {
  const [type, setType] = React.useState<BlocklistType>("PAN");
  const [value, setValue] = React.useState("");
  const [reason, setReason] = React.useState("");
  const m = useMutation({
    mutationFn: () => adminApi.addBlocklist({ type, value: value.trim(), reason: reason.trim() || `Flagged from customer #${customerId}` }),
    onSuccess: () => { setValue(""); setReason(""); toast.success("Added to blocklist"); },
  });
  return (
    <Card title="Add to blocklist (admin)" icon={<Ban size={16} />}>
      <p className="mb-3 text-xs text-muted">Flag a PAN / phone / device against fraud. Enter the full value to block.</p>
      <Select label="Type" value={type} onChange={(e) => setType(e.target.value as BlocklistType)} options={BLOCKLIST_TYPES.map((t) => ({ value: t, label: t }))} className="!mb-2" />
      <Input label="Value" value={value} onChange={(e) => setValue(e.target.value)} placeholder="e.g. ABCDE1234F" className="!mb-2" />
      <Input label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="optional" className="!mb-3" />
      {m.error && <p className="mb-2 text-sm text-error-700">{errMessage(m.error)}</p>}
      <button onClick={() => m.mutate()} disabled={m.isPending || !value.trim()} className="btn btn-sm bg-error-600 border-error-600 text-white hover:bg-error-700 btn-block disabled:opacity-50">
        {m.isPending ? <Loader2 size={13} className="animate-spin" /> : <Ban size={13} />} Add to blocklist
      </button>
    </Card>
  );
}
