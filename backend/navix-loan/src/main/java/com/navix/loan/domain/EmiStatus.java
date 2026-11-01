package com.navix.loan.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A recovery EMI conversion's state ({@code emi_conversion.status}, V104; PLAN Phase 6c).
 *
 * <pre>
 *   REQUESTED → APPROVED (ADMIN) → ACTIVE (the borrower accepts with an OTP)
 *   REQUESTED → REJECTED (ADMIN) | CANCELLED | EXPIRED
 *   APPROVED  → DECLINED (borrower) | CANCELLED | EXPIRED
 * </pre>
 */
public enum EmiStatus {
    REQUESTED, APPROVED, ACTIVE, REJECTED, CANCELLED, DECLINED, EXPIRED;

    private static final Map<EmiStatus, Set<EmiStatus>> NEXT = new EnumMap<>(EmiStatus.class);

    static {
        NEXT.put(REQUESTED, EnumSet.of(APPROVED, REJECTED, CANCELLED, EXPIRED));
        NEXT.put(APPROVED, EnumSet.of(ACTIVE, DECLINED, CANCELLED, EXPIRED));
        for (EmiStatus terminal : EnumSet.of(ACTIVE, REJECTED, CANCELLED, DECLINED, EXPIRED)) {
            NEXT.put(terminal, EnumSet.noneOf(EmiStatus.class));
        }
    }

    /** The states that hold the loan's one open slot (the partial unique index in V104). */
    public static final Set<EmiStatus> OPEN = EnumSet.of(REQUESTED, APPROVED);

    public boolean canMoveTo(EmiStatus next) {
        return NEXT.get(this).contains(next);
    }
}
