package com.navix.collections.domain;

/**
 * The one definition of the collections DPD buckets (PLAN Phase 10b, R10). Computed live from the due
 * date and never persisted. {@link #of} is the Java rule and {@link #sqlCase} the SQL one, both from
 * the same bounds; the web mirror ({@code web/src/lib/collection-buckets.ts}) is checked against this
 * file by a vitest. The RBI SMA/NPA classes are kept alongside in {@link RbiClass}.
 */
public enum DpdBucket {
    /** Not yet due. */
    UPCOMING("Upcoming", Integer.MIN_VALUE, -1),
    /** Due today (until 23:59:59 IST). */
    DUE_TODAY("Due today", 0, 0),
    D1("1 DPD", 1, 1),
    D2_7("2–7 DPD", 2, 7),
    /** The overdue-review window. */
    REVIEW("Review 8–30 DPD", 8, 30),
    D31_60("31–60 DPD", 31, 60),
    D61_90("61–90 DPD", 61, 90),
    D90_PLUS("90+ DPD", 91, Integer.MAX_VALUE);

    private final String label;
    private final int min;
    private final int max;

    DpdBucket(String label, int min, int max) {
        this.label = label;
        this.min = min;
        this.max = max;
    }

    public String label() {
        return label;
    }

    /** The bucket for {@code today − dueDate} in days (negative = not yet due). */
    public static DpdBucket of(long daysFromDue) {
        for (DpdBucket b : values()) {
            if (daysFromDue >= b.min && daysFromDue <= b.max) {
                return b;
            }
        }
        return D90_PLUS;
    }

    /** SQL CASE over an integer expression {@code daysFromDue} (today − due date), yielding the bucket name. */
    public static String sqlCase(String daysFromDue) {
        StringBuilder sb = new StringBuilder("case");
        for (DpdBucket b : values()) {
            if (b == D90_PLUS) {
                break;
            }
            sb.append(" when ").append(daysFromDue).append(" <= ").append(b.max).append(" then '").append(b.name()).append("'");
        }
        return sb.append(" else '").append(D90_PLUS.name()).append("' end").toString();
    }

    /** RBI-style ageing classes for portfolio views (standard, SMA-0/1/2, NPA), from the same day count. */
    public enum RbiClass {
        RUNNING("Running", 0), D1_30("1–30 days (SMA-0)", 30), D31_60("31–60 days (SMA-1)", 60),
        D61_90("61–90 days (SMA-2)", 90), D90_PLUS("90+ days (NPA)", Integer.MAX_VALUE);

        private final String label;
        private final int max;

        RbiClass(String label, int max) {
            this.label = label;
            this.max = max;
        }

        public String label() {
            return label;
        }

        public static RbiClass of(long daysFromDue) {
            for (RbiClass c : values()) {
                if (daysFromDue <= c.max) {
                    return c;
                }
            }
            return D90_PLUS;
        }

        public static String sqlCase(String daysFromDue) {
            StringBuilder sb = new StringBuilder("case");
            for (RbiClass c : values()) {
                if (c == D90_PLUS) {
                    break;
                }
                sb.append(" when ").append(daysFromDue).append(" <= ").append(c.max).append(" then '").append(c.name()).append("'");
            }
            return sb.append(" else '").append(D90_PLUS.name()).append("' end").toString();
        }
    }
}
