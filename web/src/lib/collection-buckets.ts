import type { DashTone, DpdBucket } from "@/lib/api/applications";

/**
 * The collections DPD buckets: a mirror of the backend's one definition
 * (`backend/navix-collections/.../domain/DpdBucket.java`, PLAN Phase 10b R10). `collection-buckets.test.ts`
 * reads that file and fails if the names, order or labels drift.
 */
export const COLLECTION_BUCKETS: ReadonlyArray<{ bucket: DpdBucket; label: string; max: number }> = [
  { bucket: "UPCOMING", label: "Upcoming", max: -1 },
  { bucket: "DUE_TODAY", label: "Due today", max: 0 },
  { bucket: "D1", label: "1 DPD", max: 1 },
  { bucket: "D2_7", label: "2–7 DPD", max: 7 },
  { bucket: "REVIEW", label: "Review 8–30 DPD", max: 30 },
  { bucket: "D31_60", label: "31–60 DPD", max: 60 },
  { bucket: "D61_90", label: "61–90 DPD", max: 90 },
  { bucket: "D90_PLUS", label: "90+ DPD", max: Number.MAX_SAFE_INTEGER },
];

/** The bucket for `today − due date` in IST days (negative = not yet due); the server's rule. */
export function bucketOfDays(daysFromDue: number): DpdBucket {
  return (COLLECTION_BUCKETS.find((b) => daysFromDue <= b.max) ?? COLLECTION_BUCKETS[COLLECTION_BUCKETS.length - 1]).bucket;
}

/** One colour per bucket, shared by the overview cards and the working page's bucket switcher. */
export const BUCKET_TONE: Record<DpdBucket, DashTone> = {
  UPCOMING: "sky",
  DUE_TODAY: "sky",
  D1: "amber",
  D2_7: "amber",
  REVIEW: "orange",
  D31_60: "red",
  D61_90: "violet",
  D90_PLUS: "navy",
};

/** Rows per bucket. Takes anything carrying a `bucket` — worklist rows today, cases historically. */
export function collectionBucketCounts(rows: ReadonlyArray<{ bucket: DpdBucket }>): Record<DpdBucket, number> {
  const counts = Object.fromEntries(COLLECTION_BUCKETS.map(({ bucket }) => [bucket, 0])) as Record<DpdBucket, number>;
  for (const item of rows) counts[item.bucket] += 1;
  return counts;
}

export function isDpdBucket(value: string | null): value is DpdBucket {
  return COLLECTION_BUCKETS.some(({ bucket }) => bucket === value);
}
