/**
 * Client-side loan segments for the staff Loans register. Unlike the customer CRM (which straddles
 * application status and loan status because a customer may not have a live loan yet), every row
 * here already IS a disbursed loan — so segmentation reads the loan's own effective status string
 * directly, no application-status fallback needed.
 */

import type { BadgeVariant } from "@/components/ui/badge";

/** Minimal shape needed for segmenting (avoids pulling the full register row type into this module). */
export type SegmentableLoan = {
  status?: string | null;
  lifecycleStatus?: string | null;
  holdStatus?: string | null;
};

/** The three partition chips (every loan in exactly one), plus the DhanNow focus chips (overlays, PLAN Phase 10b). */
export type LoanSegment = "active" | "overdue" | "closed" | "all" | FocusSegment;
export type FocusSegment = "awaitingConfirmation" | "dueToday" | "d1" | "overdueReview" | "restructure" | "onHold";

export const SEGMENT_LABEL: Record<LoanSegment, string> = {
  all: "All",
  active: "Active",
  overdue: "Overdue",
  closed: "Closed",
  awaitingConfirmation: "Awaiting confirmation",
  dueToday: "Due today",
  d1: "1 day overdue",
  overdueReview: "Overdue review",
  restructure: "Restructure",
  onHold: "On hold",
};

/** Ordered partition chips (excludes `all` which is the default list view). */
export const SEGMENTS: Exclude<LoanSegment, "all" | FocusSegment>[] = ["active", "overdue", "closed"];
/** Overlay chips on the persisted lifecycle / hold; a loan may be in one of these and a partition chip. */
export const FOCUS_SEGMENTS: FocusSegment[] = ["awaitingConfirmation", "dueToday", "d1", "overdueReview", "restructure", "onHold"];

const FOCUS_RULE: Record<FocusSegment, (r: SegmentableLoan) => boolean> = {
  awaitingConfirmation: (r) => r.lifecycleStatus === "DISBURSEMENT_PENDING",
  dueToday: (r) => r.lifecycleStatus === "DUE_TODAY",
  d1: (r) => r.lifecycleStatus === "OVERDUE_D1",
  overdueReview: (r) => r.lifecycleStatus === "OVERDUE_REVIEW",
  restructure: (r) => r.lifecycleStatus === "RESTRUCTURE_PENDING" || r.lifecycleStatus === "RESTRUCTURED",
  onHold: (r) => r.holdStatus != null && r.holdStatus !== "NONE",
};

/** Badge tone per segment — variant names from the shared `Badge` component, not raw colours. */
export const SEGMENT_TONE: Record<LoanSegment, BadgeVariant> = {
  active: "success",
  overdue: "error",
  closed: "neutral",
  all: "neutral",
  awaitingConfirmation: "info",
  dueToday: "warning",
  d1: "error",
  overdueReview: "error",
  restructure: "info",
  onHold: "warning",
};

const OVERDUE_STATUS = new Set(["OVERDUE", "IN_COLLECTIONS"]);
const CLOSED_STATUS = new Set(["REPAID", "CLOSED"]);

/**
 * Lifecycle segment for one loan row. Anything that isn't recognizably overdue or closed defaults
 * to "active" — a disbursed loan not yet closed and not (yet) flagged overdue by the backend is,
 * by construction, still live and should surface in the working queue rather than silently vanish
 * from every chip.
 */
export function segmentOf(row: Pick<SegmentableLoan, "status">): Exclude<LoanSegment, "all"> {
  const status = row.status ?? null;

  if (status && OVERDUE_STATUS.has(status)) return "overdue";
  if (status && CLOSED_STATUS.has(status)) return "closed";

  return "active";
}

export function inSegment(row: SegmentableLoan, seg: LoanSegment): boolean {
  if (seg === "all") return true;
  if ((FOCUS_SEGMENTS as string[]).includes(seg)) return FOCUS_RULE[seg as FocusSegment](row);
  return segmentOf(row) === seg;
}

export type SegmentCounts = Record<LoanSegment, number>;

/** One pass over rows → counts per segment (and `all === rows.length`). */
export function segmentCounts(rows: SegmentableLoan[]): SegmentCounts {
  const counts = Object.fromEntries([...SEGMENTS, ...FOCUS_SEGMENTS].map((s) => [s, 0])) as SegmentCounts;
  counts.all = rows.length;
  for (const row of rows) {
    counts[segmentOf(row)]++;
    for (const f of FOCUS_SEGMENTS) if (FOCUS_RULE[f](row)) counts[f]++;
  }
  return counts;
}
