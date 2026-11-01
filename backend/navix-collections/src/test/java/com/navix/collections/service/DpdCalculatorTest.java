package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.collections.domain.DpdBucket;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link DpdCalculator}: days-past-due arithmetic and bucket
 * banding across every band boundary. No mocks — constructed directly.
 */
class DpdCalculatorTest {

    private final DpdCalculator calc = new DpdCalculator();

    private static final LocalDate DUE = LocalDate.of(2026, 6, 1);

    @Test
    void daysPastDueIsZeroBeforeAndOnDueDate() {
        assertThat(calc.daysPastDue(DUE, DUE.minusDays(5))).isZero();
        assertThat(calc.daysPastDue(DUE, DUE)).isZero();
    }

    @Test
    void daysPastDueCountsDaysAfterDueDate() {
        assertThat(calc.daysPastDue(DUE, DUE.plusDays(1))).isEqualTo(1);
        assertThat(calc.daysPastDue(DUE, DUE.plusDays(45))).isEqualTo(45);
    }

    @Test
    void daysPastDueNullSafe() {
        assertThat(calc.daysPastDue(null, DUE)).isZero();
        assertThat(calc.daysPastDue(DUE, null)).isZero();
    }

    @Test
    void bucketsFollowTheSharedDefinitionAtEveryEdge() {
        assertThat(calc.bucket(-10)).isEqualTo(DpdBucket.UPCOMING);
        assertThat(calc.bucket(-1)).isEqualTo(DpdBucket.UPCOMING);
        assertThat(calc.bucket(0)).isEqualTo(DpdBucket.DUE_TODAY);
        assertThat(calc.bucket(1)).isEqualTo(DpdBucket.D1);
        assertThat(calc.bucket(2)).isEqualTo(DpdBucket.D2_7);
        assertThat(calc.bucket(7)).isEqualTo(DpdBucket.D2_7);
        assertThat(calc.bucket(8)).isEqualTo(DpdBucket.REVIEW);
        assertThat(calc.bucket(30)).isEqualTo(DpdBucket.REVIEW);
        assertThat(calc.bucket(31)).isEqualTo(DpdBucket.D31_60);
        assertThat(calc.bucket(60)).isEqualTo(DpdBucket.D31_60);
        assertThat(calc.bucket(61)).isEqualTo(DpdBucket.D61_90);
        assertThat(calc.bucket(90)).isEqualTo(DpdBucket.D61_90);
        assertThat(calc.bucket(91)).isEqualTo(DpdBucket.D90_PLUS);
        assertThat(calc.bucket(365)).isEqualTo(DpdBucket.D90_PLUS);
    }

    @Test
    void bucketForUsesTheDueDateSoUpcomingAndDueTodayDiffer() {
        assertThat(calc.bucketFor(DUE, DUE.minusDays(3))).isEqualTo(DpdBucket.UPCOMING);
        assertThat(calc.bucketFor(DUE, DUE)).isEqualTo(DpdBucket.DUE_TODAY);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(5))).isEqualTo(DpdBucket.D2_7);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(20))).isEqualTo(DpdBucket.REVIEW);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(100))).isEqualTo(DpdBucket.D90_PLUS);
        assertThat(calc.bucketFor(null, DUE)).isEqualTo(DpdBucket.UPCOMING);
    }

    @Test
    void theRbiClassesAreKeptAlongside() {
        assertThat(DpdBucket.RbiClass.of(0)).isEqualTo(DpdBucket.RbiClass.RUNNING);
        assertThat(DpdBucket.RbiClass.of(30)).isEqualTo(DpdBucket.RbiClass.D1_30);
        assertThat(DpdBucket.RbiClass.of(91)).isEqualTo(DpdBucket.RbiClass.D90_PLUS);
        assertThat(DpdBucket.RbiClass.sqlCase("x")).isEqualTo("case when x <= 0 then 'RUNNING' when x <= 30 then 'D1_30'"
                + " when x <= 60 then 'D31_60' when x <= 90 then 'D61_90' else 'D90_PLUS' end");
    }
}
