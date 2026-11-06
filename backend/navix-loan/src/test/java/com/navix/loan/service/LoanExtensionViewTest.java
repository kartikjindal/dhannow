package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.ExtensionDirectory.ExtensionRef;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PLAN Phase 11: the borrower's loan read carries the account number, days left (IST), charges paid and the officer. */
class LoanExtensionViewTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 20:00 UTC on 2 Nov is already 3 Nov in India: days left count from the IST date. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T20:00:00Z"), IST);

    private final ExtensionDirectory extensions = mock(ExtensionDirectory.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final LoanExtensionView view = new LoanExtensionView(extensions, mock(LoanExtensionLedger.class),
            TestPricing.navix(), CLOCK, staff, payments);

    private static ExtensionRef ext(int seq, String status, Long proposedBy, Long paymentId) {
        Instant future = Instant.parse("2026-11-05T00:00:00Z");
        return new ExtensionRef(UUID.randomUUID(), 5L, seq, status, 0L, 10_000L, 1_800L, 14_000L,
                LocalDate.of(2026, 11, 4), future, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15),
                proposedBy, 8L, Instant.parse("2026-11-01T00:00:00Z"), null, null, null, null, paymentId, null);
    }

    @Test
    void theLoanReadCarriesTheNumberDaysLeftChargesPaidAndTheOfficersFirstName() {
        Loan loan = new Loan();
        loan.setId(123L);
        loan.setPrincipal(100_000L);
        loan.setDueDate(LocalDate.of(2026, 11, 17));
        Payment paid = new Payment();
        paid.setId(40L);
        paid.setAmount(31_800L);
        when(extensions.borrowerHistory(123L)).thenReturn(List.of(ext(1, "ACTIVE", 7L, 40L), ext(2, "APPROVED", 7L, null)));
        when(payments.findAllById(any())).thenReturn(List.of(paid));
        when(staff.findStaff(7L)).thenReturn(Optional.of(new StaffSummary(7L, "  Ravi Kumar Singh ", "COLLECTION_EXECUTIVE", true)));

        LoanView v = view.attach(loan, LoanView.of(loan, 100_000L, null));

        assertThat(v.loanAccountNumber()).isEqualTo("DNL0000123");
        // 2026-11-03 in IST (the UTC date is still the 2nd) to the 17th.
        assertThat(v.daysLeft()).isEqualTo(14);
        assertThat(v.extensions()).extracting(e -> e.chargePaidPaise()).containsExactly(31_800L, null);
        assertThat(v.pendingExtension().offeredBy()).isEqualTo("Ravi");
    }
}
