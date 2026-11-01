package com.navix.common.security;

import com.navix.common.exception.BusinessException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Enforces maker-checker separation of duties: across a sequence of maker-checker steps on the same
 * entity, no single actor may perform two steps. A repeat raises {@link BusinessException} with code
 * {@code SOD_VIOLATION} (HTTP 422 through the global handler).
 *
 * <p>Roles play no part here, so ADMIN gets no exemption: ADMIN still passes the role checks, but an
 * ADMIN who sanctioned a loan cannot also release it (PLAN Phase 4).
 *
 * <p>The DhanNow chains (PLAN Phase 4), one rule per checker step:
 * <ul>
 *   <li>disbursal: sanctioner ≠ disburser (Disbursement Head, UTR) ≠ confirmer (Accountant);</li>
 *   <li>repayment: payment recorder ≠ verifier;</li>
 *   <li>recovery EMI: requester ≠ approver (Phase 6);</li>
 *   <li>loan extension: proposer ≠ approver (Collection Head) ≠ payment verifier (Accountant) (Phase 6).</li>
 * </ul>
 * Null prior ids are a step not yet taken (or taken by no staff member, e.g. the borrower), and are
 * ignored. A null acting id is itself a violation: every checker step needs an identifiable actor.
 */
public final class SeparationOfDutiesGuard {

    /** Stable error code for a separation-of-duties breach. */
    public static final String VIOLATION_CODE = "SOD_VIOLATION";

    private SeparationOfDutiesGuard() {
    }

    /**
     * Reject if {@code actorId} already appears among the actors who performed prior maker-checker
     * steps on the same entity.
     */
    public static void enforce(Long actorId, Collection<Long> priorActorIds) {
        if (actorId == null) {
            throw new BusinessException(VIOLATION_CODE,
                    "Acting staff member is required for separation-of-duties enforcement");
        }
        if (priorActorIds == null) {
            return;
        }
        boolean repeats = priorActorIds.stream()
                .filter(Objects::nonNull)
                .anyMatch(prior -> prior.equals(actorId));
        if (repeats) {
            throw new BusinessException(VIOLATION_CODE,
                    "You already performed an earlier step on this item. Another staff member must do this one.");
        }
    }

    /** Varargs convenience over {@link #enforce(Long, Collection)}. */
    public static void enforce(Long actorId, Long... priorActorIds) {
        enforce(actorId, priorActorIds == null ? null : Arrays.asList(priorActorIds));
    }

    /** The Disbursement Head entering the UTR did not sanction the loan. */
    public static void disburser(Long disburserId, Long sanctionerId) {
        enforce(disburserId, sanctionerId);
    }

    /** The Accountant confirming (or returning) the disbursal neither sanctioned nor released it. */
    public static void disbursalConfirmer(Long confirmerId, Long sanctionerId, Long disburserId) {
        enforce(confirmerId, sanctionerId, disburserId);
    }

    /** Whoever verifies a payment did not record it. */
    public static void paymentVerifier(Long verifierId, Long recorderId) {
        enforce(verifierId, recorderId);
    }

    /** Phase 6: a recovery-EMI conversion is approved by someone other than its requester. */
    public static void emiApprover(Long approverId, Long requesterId) {
        enforce(approverId, requesterId);
    }

    /** Phase 6: the Collection Head approving an extension did not propose it. */
    public static void extensionApprover(Long approverId, Long proposerId) {
        enforce(approverId, proposerId);
    }

    /** Phase 6: the Accountant verifying an extension payment neither proposed nor approved it. */
    public static void extensionPaymentVerifier(Long verifierId, Long proposerId, Long approverId) {
        enforce(verifierId, proposerId, approverId);
    }
}
