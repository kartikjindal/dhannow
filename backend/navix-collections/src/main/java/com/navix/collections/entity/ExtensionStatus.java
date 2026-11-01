package com.navix.collections.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A loan extension's state ({@code loan_extension.status}, V103; PLAN Phase 6b).
 *
 * <pre>
 *   OFFERED → APPROVED → ACCEPTED → PAYMENT_PENDING → ACTIVE
 *   OFFERED → REJECTED (Head) | CANCELLED (officer) | EXPIRED (pay-by passed)
 *   APPROVED / ACCEPTED → EXPIRED | CANCELLED | DECLINED (borrower)
 *   PAYMENT_PENDING → ACCEPTED (payment rejected) | CANCELLED (the loan closed)
 * </pre>
 */
public enum ExtensionStatus {
    OFFERED, APPROVED, ACCEPTED, PAYMENT_PENDING, ACTIVE, REJECTED, CANCELLED, EXPIRED, DECLINED;

    private static final Map<ExtensionStatus, Set<ExtensionStatus>> NEXT = new EnumMap<>(ExtensionStatus.class);

    static {
        NEXT.put(OFFERED, EnumSet.of(APPROVED, REJECTED, CANCELLED, EXPIRED));
        NEXT.put(APPROVED, EnumSet.of(ACCEPTED, EXPIRED, CANCELLED, DECLINED));
        NEXT.put(ACCEPTED, EnumSet.of(PAYMENT_PENDING, EXPIRED, CANCELLED, DECLINED));
        NEXT.put(PAYMENT_PENDING, EnumSet.of(ACTIVE, ACCEPTED, CANCELLED));
        for (ExtensionStatus terminal : EnumSet.of(ACTIVE, REJECTED, CANCELLED, EXPIRED, DECLINED)) {
            NEXT.put(terminal, EnumSet.noneOf(ExtensionStatus.class));
        }
    }

    /** The states that hold the loan's one open slot (the partial unique index in V103). */
    public static final Set<ExtensionStatus> OPEN = EnumSet.of(OFFERED, APPROVED, ACCEPTED, PAYMENT_PENDING);

    public boolean canMoveTo(ExtensionStatus next) {
        return NEXT.get(this).contains(next);
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
