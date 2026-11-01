/**
 * Staff desks added in PLAN Phase 10 (split out of `applications.ts`, which stays as it is). Every call goes
 * through the allowlisted `/api/staff/desk/*` proxy, or the existing applications / collections proxies.
 * Money is integer paise from the backend; the console only formats it.
 */
import { bff } from "@/lib/api/applications";

const D = "/api/staff/desk";
const q = (p: Record<string, string | number | boolean | null | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(p)) if (v !== undefined && v !== null && v !== "") s.set(k, String(v));
  const t = s.toString();
  return t ? `?${t}` : "";
};

// ---- Disbursal confirmation (Phase 4, Accountant) ----------------------------------------------------

export const disbursalApi = {
  confirm: (applicationId: number) => bff<unknown>(`/api/staff/applications/${applicationId}/disbursal/confirm`, "POST"),
  /** Wrong UTR or money not credited: back to the Disbursement Head (reason required). */
  returnToHead: (applicationId: number, reason: string) =>
    bff<unknown>(`/api/staff/applications/${applicationId}/disbursal/return`, "POST", { reason }),
};

// ---- Refund cases (Phase 6a/6c) ---------------------------------------------------------------------

export interface RefundCase {
  id: number;
  loanId: number;
  customerId: number;
  paymentId: number | null;
  mandateDebitId: number | null;
  amountPaise: number;
  reason: "OVERPAYMENT" | "NACH_AFTER_CLOSE";
  status: "OPEN" | "REFUNDED" | "CANCELLED";
  createdAt: string;
  refundUtr: string | null;
  refundedOn: string | null;
  cancelReason: string | null;
}

export const refundCasesApi = {
  list: (status?: string) => bff<RefundCase[]>(`${D}/refund-cases${q({ status })}`, "GET"),
  refunded: (id: number, utr: string, refundedOn: string) =>
    bff<RefundCase>(`${D}/refund-cases/${id}/refunded`, "POST", { utr, refundedOn }),
  cancel: (id: number, reason: string) => bff<RefundCase>(`${D}/refund-cases/${id}/cancel`, "POST", { reason }),
};

/** CC-10: reverse a verified repayment (ACCOUNTANT / FINANCE / ADMIN; never its recorder or verifier). */
export const reversePayment = (loanId: number, paymentId: number, reason: string) =>
  bff<unknown>(`${D}/loan/${loanId}/repayments/${paymentId}/reverse`, "POST", { reason });

/** EXT-14: re-file a pending repayment as the loan's ACCEPTED extension payment (must equal the quote for its bank date). */
export const treatAsExtensionPayment = (loanId: number, paymentId: number, extensionId: string) =>
  bff<unknown>(`${D}/loan/${loanId}/repayments/${paymentId}/treat-as-extension`, "POST", { extensionId });

// ---- Complaints (Phase 7) ---------------------------------------------------------------------------

export const COMPLAINT_CATEGORIES = [
  "PAYMENT", "CHARGES", "COLLECTIONS_CONDUCT", "LOAN_DISPUTE", "EXTENSION_NOT_REQUESTED", "DATA_PRIVACY", "APP_OR_SERVICE", "OTHER",
] as const;
export type ComplaintStatus = "OPEN" | "ACKNOWLEDGED" | "IN_REVIEW" | "RESOLVED" | "ESCALATED";

export interface Complaint {
  id: number;
  ticketNo: string;
  customerId: number;
  loanId: number | null;
  category: string;
  body: string;
  attachmentKey: string | null;
  source: "BORROWER" | "STAFF" | "SYSTEM";
  status: ComplaintStatus;
  createdAt: string;
  ackDueAt: string;
  reviewDueAt: string;
  resolveDueAt: string;
  acknowledgedAt: string | null;
  reviewStartedAt: string | null;
  resolvedAt: string | null;
  escalatedAt: string | null;
  resolutionNote: string | null;
  assignedTo: number | null;
  breached: string[];
  version: number;
}
export interface ComplaintEvent { action: string; fromStatus: string | null; toStatus: string | null; note: string | null; actorId: string | null; actorRole: string | null; at: string }

export const complaintsApi = {
  list: (p: { status?: string; category?: string; customerId?: number; breached?: boolean } = {}) =>
    bff<Complaint[]>(`${D}/staff/complaints${q(p)}`, "GET"),
  get: (id: number) => bff<{ complaint: Complaint; events: ComplaintEvent[] }>(`${D}/staff/complaints/${id}`, "GET"),
  file: (body: { customerId: number; category: string; body: string; loanId?: number }) =>
    bff<Complaint>(`${D}/staff/complaints`, "POST", body),
  acknowledge: (id: number, version: number, note?: string) =>
    bff<Complaint>(`${D}/staff/complaints/${id}/acknowledge`, "POST", { note, version }),
  review: (id: number, version: number, note?: string) =>
    bff<Complaint>(`${D}/staff/complaints/${id}/status`, "POST", { status: "IN_REVIEW", note, version }),
  resolve: (id: number, version: number, note: string) =>
    bff<Complaint>(`${D}/staff/complaints/${id}/resolve`, "POST", { note, version }),
  escalate: (id: number, version: number, note: string) =>
    bff<Complaint>(`${D}/staff/complaints/${id}/escalate`, "POST", { note, version }),
  assign: (id: number, staffId: number) => bff<Complaint>(`${D}/staff/complaints/${id}/assign`, "POST", { staffId }),
};

// ---- Disputes / holds (Phase 7) ---------------------------------------------------------------------

export interface HoldEvent { fromHold: string; toHold: string; reason: string | null; complaintId: number | null; actorId: string | null; actorRole: string | null; at: string }

export const holdsApi = {
  history: (loanId: number) => bff<HoldEvent[]>(`${D}/loans/${loanId}/holds`, "GET"),
  raise: (loanId: number, reason: string) => bff<unknown>(`${D}/loans/${loanId}/dispute`, "POST", { reason }),
  release: (loanId: number, note: string) => bff<unknown>(`${D}/loans/${loanId}/dispute/release`, "POST", { note }),
};

// ---- Holiday calendar (Phase 7, CC-64) --------------------------------------------------------------

export interface Holiday { day: string; name: string }
export const holidaysApi = {
  list: () => bff<Holiday[]>(`${D}/admin/holidays`, "GET"),
  add: (day: string, name: string) => bff<Holiday[]>(`${D}/admin/holidays/${day}`, "PUT", { name }),
  remove: (day: string) => bff<Holiday[]>(`${D}/admin/holidays/${day}`, "DELETE"),
};

// ---- Selfie review (Phase 8) ------------------------------------------------------------------------

export interface SelfieReview {
  applicationId: number;
  customerId: number;
  borrowerName: string | null;
  submittedAt: string;
  attempts: number;
  frames: { straight?: string; right?: string; left?: string };
  message: string | null;
}
export const selfieReviewsApi = {
  list: () => bff<SelfieReview[]>(`${D}/staff/selfie-reviews`, "GET"),
  approve: (applicationId: number, note?: string) => bff<unknown>(`${D}/staff/selfie-reviews/${applicationId}/approve`, "POST", { note }),
  reject: (applicationId: number, reason: string) => bff<unknown>(`${D}/staff/selfie-reviews/${applicationId}/reject`, "POST", { reason }),
};

// ---- Credit ladder (Phase 2) ------------------------------------------------------------------------

export interface LadderQuote {
  principalPaise: number;
  feePaise: number;
  gstPaise: number;
  interestPaise: number;
  netPaise: number;
  repayPaise: number;
  tenureDays: number;
}
export interface CreditLevelDetail {
  current: { level: number; maxPaise: number; nextLevelPaise: number | null; ladder: number[] };
  history: { fromLevel: number | null; toLevel: number; reason: string; loanId: number | null; reasonCode: string | null; approvedByName: string | null; at: string; reversedAt: string | null }[];
  quotes: LadderQuote[];
}
export const creditLevelApi = {
  get: (customerId: number) => bff<CreditLevelDetail>(`${D}/staff/customers/${customerId}/credit-level`, "GET"),
  /** ADMIN only, with a reason code; audited in the level history. */
  setException: (customerId: number, level: number, reasonCode: string) =>
    bff<unknown>(`${D}/customers/${customerId}/credit-level`, "POST", { level, reasonCode }),
};

// ---- Balance-adjustment log (Phase 7, Compliance reads) ---------------------------------------------

export interface BalanceAdjustment {
  id: number;
  loanId: number;
  customerId: number;
  kind: string;
  reasonCode: string;
  reasonLabel: string | null;
  note: string | null;
  before: string;
  after: string;
  scheduleVersionFrom: number | null;
  scheduleVersionTo: number | null;
  approverId: number | null;
  approverName: string | null;
  at: string;
}
export const adjustmentsApi = {
  list: (p: { loanId?: number; customerId?: number; kind?: string } = {}) =>
    bff<BalanceAdjustment[]>(`${D}/staff/balance-adjustments${q(p)}`, "GET"),
};

// ---- Pricing / product config maker-checker (Phase 1 config, Phase 10 screen) -----------------------

export type ConfigRow = Record<string, unknown> & { id: number; version: number; status: "DRAFT" | "ACTIVE" | "RETIRED"; createdBy: string | null };
export interface ConfigView { pricing: ConfigRow[]; product: ConfigRow[] }
export const configApi = {
  get: () => bff<ConfigView>(`${D}/staff/config`, "GET"),
  draft: (kind: "pricing" | "product", terms: Record<string, number>) => bff<ConfigView>(`${D}/staff/config/${kind}`, "POST", terms),
  approve: (kind: "pricing" | "product", id: number) => bff<ConfigView>(`${D}/staff/config/${kind}/${id}/approve`, "POST"),
  discard: (kind: "pricing" | "product", id: number) => bff<ConfigView>(`${D}/staff/config/${kind}/${id}/discard`, "POST"),
};

// ---- Loan extension (Phase 6b, wireframe row G) -----------------------------------------------------

export interface ExtensionQuote { feePaise: number; gstPaise: number; interestPaise: number; lateChargePaise: number; lateDays: number; paidOn: string; totalPaise: number }
export type ExtensionStatus = "OFFERED" | "APPROVED" | "ACCEPTED" | "PAYMENT_PENDING" | "ACTIVE" | "REJECTED" | "DECLINED" | "EXPIRED" | "CANCELLED";
export interface Extension {
  id: string;
  loanId: number;
  caseId: string | null;
  seq: number;
  status: ExtensionStatus;
  version: number;
  feePaise: number;
  gstPaise: number;
  interestPaise: number;
  payBy: string | null;
  expiresAt: string | null;
  oldDueDate: string | null;
  newDueDate: string | null;
  callNote: string | null;
  overrideReason: string | null;
  proposedBy: number | null;
  proposedAt: string | null;
  approvedBy: number | null;
  approvedAt: string | null;
  rejectReason: string | null;
  cancelReason: string | null;
  acceptedAt: string | null;
  declinedReason: string | null;
  seenByBorrowerAt: string | null;
  paymentId: number | null;
  activatedAt: string | null;
}
export interface ExtensionPanel {
  caseId: string;
  loanId: number;
  eligible: boolean;
  reasonCode: string | null;
  cap: number;
  used: number;
  quoteForToday: ExtensionQuote | null;
  dueDate: string | null;
  originalDueDate: string | null;
  newDueDate: string | null;
  current: Extension | null;
  history: Extension[];
}
const C = "/api/staff/collections";
export const extensionsApi = {
  panel: (caseId: string) => bff<ExtensionPanel>(`${C}/cases/${caseId}/extensions`, "GET"),
  /** G1: officer offers (pay-by today … before the new due date); ADMIN may pass the cap with a reason. */
  offer: (caseId: string, body: { payBy: string; callNote: string; recordingId?: string; overrideReason?: string }) =>
    bff<Extension>(`${C}/cases/${caseId}/extensions`, "POST", body),
  /** G2: Collection Head approves (never their own offer) or rejects. */
  approve: (id: string) => bff<Extension>(`${C}/extensions/${id}/approve`, "POST"),
  reject: (id: string, reason: string, note?: string) => bff<Extension>(`${C}/extensions/${id}/reject`, "POST", { reason, note }),
  cancel: (id: string, reason: string) => bff<Extension>(`${C}/extensions/${id}/cancel`, "POST", { reason }),
  resend: (id: string) => bff<Extension>(`${C}/extensions/${id}/resend`, "POST"),
};

// ---- Recovery EMI (Phase 6c, flag emi-conversion) ---------------------------------------------------

export interface EmiRequest {
  id: string;
  loanId: number;
  status: string;
  version: number;
  instalmentCount: number;
  acceptBy: string | null;
  [k: string]: unknown;
}
export interface EmiPanel {
  loanId: number;
  eligible: boolean;
  reasonCode: string | null;
  owedTodayPaise: number;
  previewForToday: { seq: number; dueDate: string; principalPaise: number; chargesPaise: number; totalPaise: number }[];
  current: EmiRequest | null;
  history: EmiRequest[];
}
export const emiApi = {
  queue: (status = "REQUESTED") => bff<EmiRequest[]>(`${C}/emi${q({ status })}`, "GET"),
  panel: (loanId: number) => bff<EmiPanel>(`${C}/loans/${loanId}/emi`, "GET"),
  request: (loanId: number, acceptBy: string, note: string) => bff<EmiRequest>(`${C}/loans/${loanId}/emi`, "POST", { acceptBy, note }),
  /** ADMIN only, never the requester. */
  approve: (id: string) => bff<EmiRequest>(`${C}/emi/${id}/approve`, "POST"),
  reject: (id: string, reason: "NOT_ELIGIBLE" | "SETTLE_INSTEAD" | "PAYMENT_HISTORY" | "OTHER", note?: string) =>
    bff<EmiRequest>(`${C}/emi/${id}/reject`, "POST", { reason, note }),
  cancel: (id: string, reason: string) => bff<EmiRequest>(`${C}/emi/${id}/cancel`, "POST", { reason }),
};

// ---- Staff MFA (Phase 7) ----------------------------------------------------------------------------

async function mfa<T>(action: "enrol" | "confirm", body: Record<string, string>): Promise<T> {
  const res = await fetch(`/api/auth/staff/mfa/${action}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const json = (await res.json().catch(() => null)) as { success?: boolean; data?: T; error?: { message?: string } } | null;
  if (!res.ok || json?.success === false) throw new Error(json?.error?.message ?? "Could not complete MFA set-up.");
  return json?.data as T;
}
export const mfaApi = {
  enrol: (email: string, password: string) => mfa<{ secret: string; otpauthUri: string }>("enrol", { email, password }),
  confirm: (email: string, password: string, code: string) => mfa<unknown>("confirm", { email, password, code }),
  /** ADMIN resets another staffer's authenticator (never their own). */
  reset: (staffId: number) => bff<unknown>(`${D}/admin/staff/${staffId}/mfa/reset`, "POST"),
};

// ---- Stored loan documents (Phase 5): the same PDFs the borrower saw, never re-rendered -------------

export interface StoredLoanDocument { id: number; type: string; version: number; createdAt: string; acceptedAt: string | null; sha256: string | null }
export const loanDocumentsApi = {
  list: (loanId: number) => bff<StoredLoanDocument[]>(`${D}/loans/${loanId}/documents`, "GET"),
  /** A 5-minute presigned link; each staff open is written to `loan_document_access`. */
  url: (loanId: number, docId: number) => bff<{ documentId: number; url: string; expiresInSeconds: number }>(`${D}/loans/${loanId}/documents/${docId}/url`, "GET"),
};
