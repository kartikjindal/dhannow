import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { COLLECTION_BUCKETS, bucketOfDays, collectionBucketCounts } from "./collection-buckets";

const row = (bucket: (typeof COLLECTION_BUCKETS)[number]["bucket"], id: string) => ({ bucket, id });

describe("collection buckets", () => {
  it("counts rows per bucket, zero-filled", () => {
    const counts = collectionBucketCounts([row("REVIEW", "a"), row("REVIEW", "b"), row("UPCOMING", "c")]);
    expect(counts.REVIEW).toBe(2);
    expect(counts.UPCOMING).toBe(1);
    expect(counts.D90_PLUS).toBe(0);
  });

  it("mirrors the backend's one definition (PLAN Phase 10b, R10)", () => {
    const java = fs.readFileSync(
      path.resolve(__dirname, "../../../backend/navix-collections/src/main/java/com/navix/collections/domain/DpdBucket.java"),
      "utf8",
    );
    const bound = (v: string) => (v === "Integer.MAX_VALUE" ? Number.MAX_SAFE_INTEGER : Number(v));
    const backend = [...java.matchAll(/^\s{4}([A-Z0-9_]+)\("([^"]+)", [^,]+, ([^)]+)\)/gm)].map((m) => ({
      bucket: m[1],
      label: m[2],
      max: bound(m[3].trim()),
    }));
    expect(backend).toEqual(COLLECTION_BUCKETS);
  });

  it("buckets by days from the due date at every edge", () => {
    expect([-3, -1, 0, 1, 2, 7, 8, 30, 31, 60, 61, 90, 91].map(bucketOfDays)).toEqual([
      "UPCOMING", "UPCOMING", "DUE_TODAY", "D1", "D2_7", "D2_7", "REVIEW", "REVIEW", "D31_60", "D31_60", "D61_90", "D61_90", "D90_PLUS",
    ]);
  });
});
