package com.navix.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SeparationOfDutiesGuardTest {

    private static void rejects(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(SeparationOfDutiesGuard.VIOLATION_CODE);
    }

    @Test
    void enforcePassesWhenActorIsDistinctFromPriors() {
        assertThatCode(() -> SeparationOfDutiesGuard.enforce(3L, List.of(1L, 2L))).doesNotThrowAnyException();
    }

    @Test
    void enforceRejectsRepeatedActor() {
        rejects(() -> SeparationOfDutiesGuard.enforce(2L, List.of(1L, 2L)));
        rejects(() -> SeparationOfDutiesGuard.enforce(1L, 1L));
    }

    @Test
    void enforceIgnoresNullOrMissingPriors() {
        assertThatCode(() -> SeparationOfDutiesGuard.enforce(5L, Arrays.asList(null, 1L, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> SeparationOfDutiesGuard.enforce(5L, List.of())).doesNotThrowAnyException();
        assertThatCode(() -> SeparationOfDutiesGuard.enforce(5L, (java.util.Collection<Long>) null))
                .doesNotThrowAnyException();
    }

    @Test
    void enforceRejectsNullActor() {
        rejects(() -> SeparationOfDutiesGuard.enforce(null, List.of(1L)));
    }

    /** PLAN Phase 4: one rule per checker step, each pair rejected. */
    @Test
    void everyPhaseFourPairIsRejected() {
        rejects(() -> SeparationOfDutiesGuard.disburser(1L, 1L));
        rejects(() -> SeparationOfDutiesGuard.disbursalConfirmer(1L, 1L, 2L));
        rejects(() -> SeparationOfDutiesGuard.disbursalConfirmer(2L, 1L, 2L));
        rejects(() -> SeparationOfDutiesGuard.paymentVerifier(3L, 3L));
        rejects(() -> SeparationOfDutiesGuard.emiApprover(4L, 4L));
        rejects(() -> SeparationOfDutiesGuard.extensionApprover(5L, 5L));
        rejects(() -> SeparationOfDutiesGuard.extensionPaymentVerifier(5L, 5L, 6L));
        rejects(() -> SeparationOfDutiesGuard.extensionPaymentVerifier(6L, 5L, 6L));
    }

    @Test
    void distinctActorsPassEveryRule() {
        assertThatCode(() -> {
            SeparationOfDutiesGuard.disburser(2L, 1L);
            SeparationOfDutiesGuard.disbursalConfirmer(3L, 1L, 2L);
            SeparationOfDutiesGuard.paymentVerifier(3L, null);
            SeparationOfDutiesGuard.emiApprover(4L, 5L);
            SeparationOfDutiesGuard.extensionApprover(6L, 5L);
            SeparationOfDutiesGuard.extensionPaymentVerifier(7L, 5L, 6L);
        }).doesNotThrowAnyException();
    }
}
