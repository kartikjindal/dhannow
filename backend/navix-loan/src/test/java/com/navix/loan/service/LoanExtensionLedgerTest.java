package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.ExtensionDirectory.ExtensionRef;
import com.navix.common.exception.BusinessException;
import com.navix.common.extension.ExtensionFacts;
import com.navix.common.extension.ExtensionQuote;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.domain.PaymentPurpose;
import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.MandateDebitRepository;
import com.navix.loan.repository.PaymentRepository;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** PLAN Phase 6b: the extension quote, eligibility and the payment guard on the loan side. */
class LoanExtensionLedgerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).atZone(IST).toInstant(), IST);

    private final LoanRepository loans = mock(LoanRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final MandateDebitRepository debits = mock(MandateDebitRepository.class);
    private final ExtensionDirectory extensions = mock(ExtensionDirectory.class);
    private final com.navix.loan.repository.EmiConversionRepository emis =
            mock(com.navix.loan.repository.EmiConversionRepository.class);
    private final PricingService pricing = dhanNowPricing();
    private final LoanMath math = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);
    private LoanExtensionLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new LoanExtensionLedger(loans, payments,
                emis, debits, pricing, mock(RepaymentScheduleService.class),
                extensions, mock(JdbcTemplate.class), e -> { }, new ObjectMapper(), CLOCK);
        ActorContext.set(new CurrentActor("7", "Borrower", "BORROWER"));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    /** CC-15: ₹1,000 paid 3 days late = 100 fee + 18 GST + 140 interest + 60 late charge = ₹318. */
    @Test
    void cc15_quoteForOneThousandPaidThreeDaysLateIs318() {
        Loan loan = loan(TODAY.minusDays(3));
        ExtensionQuote q = LoanExtensionLedger.quote(loan, math, TODAY, 0L);
        assertThat(q.feePaise()).isEqualTo(10_000L);
        assertThat(q.gstPaise()).isEqualTo(1_800L);
        assertThat(q.interestPaise()).isEqualTo(14_000L);
        assertThat(q.lateChargePaise()).isEqualTo(6_000L);
        assertThat(q.lateDays()).isEqualTo(3);
        assertThat(q.totalPaise()).isEqualTo(31_800L);
        // On the due date itself: ₹258 per ₹1,000.
        assertThat(LoanExtensionLedger.quote(loan, math, loan.getDueDate(), 0L).totalPaise()).isEqualTo(25_800L);
    }

    /** CC-16: a late extension still buys previous due + 14, so it buys fewer remaining days. */
    @Test
    void cc16_newDueDateIsThePreviousDuePlusFourteen() {
        Loan loan = loan(TODAY.minusDays(3));
        assertThat(ledger.newDueDate(loan)).isEqualTo(TODAY.minusDays(3).plusDays(14));
    }

    /** Late charge already paid by a part repayment is not charged again by the extension. */
    @Test
    void cc15_lateChargeAlreadyPaidByARepaymentIsNotChargedAgain() {
        Loan loan = loan(TODAY.minusDays(3));
        assertThat(LoanExtensionLedger.quote(loan, math, TODAY, 6_000L).lateChargePaise()).isZero();
        assertThat(LoanExtensionLedger.quote(loan, math, TODAY, 2_000L).lateChargePaise()).isEqualTo(4_000L);
    }

    /** EXT-26: the 30-day cap counts penal days over the loan's whole life; once used up the quote shows ₹0. */
    @Test
    void ext26_theLateChargeCapIsALifetimeCapAcrossCycles() {
        Loan loan = loan(TODAY.minusDays(5));
        loan.setPenalDaysUsed(28);
        ExtensionQuote q = LoanExtensionLedger.quote(loan, math, TODAY, 0L);
        assertThat(q.lateDays()).isEqualTo(2);
        assertThat(q.lateChargePaise()).isEqualTo(4_000L);
        loan.setPenalDaysUsed(30);
        assertThat(LoanExtensionLedger.quote(loan, math, TODAY, 0L).lateChargePaise()).isZero();
        assertThat(LoanExtensionLedger.quote(loan, math, TODAY, 0L).totalPaise()).isEqualTo(25_800L);
    }

    /** EXT-02: each ineligible loan names its reason; a due or overdue loan is eligible. */
    @Test
    void ext02_anIneligibleLoanGetsASpecificReasonCode() {
        Loan notDue = loan(TODAY.plusDays(2));
        assertThat(facts(notDue).ineligibleReason()).isEqualTo("NOT_DUE_YET");

        Loan due = loan(TODAY);
        assertThat(facts(due).ineligibleReason()).isNull();
        assertThat(facts(due).lifecycleStatus()).isEqualTo("DUE_TODAY");

        Loan held = loan(TODAY.minusDays(1));
        held.setHoldStatus(LoanHold.DISPUTED);
        assertThat(facts(held).ineligibleReason()).isEqualTo("ON_HOLD");

        Loan pending = loan(TODAY.minusDays(1));
        Payment p = new Payment();
        p.setStatus(PaymentStatus.PENDING_VERIFICATION);
        when(payments.findByLoanId(1L)).thenReturn(List.of(p));
        assertThat(facts(pending).ineligibleReason()).isEqualTo("REPAYMENT_PENDING");
        when(payments.findByLoanId(1L)).thenReturn(List.of());

        when(debits.existsByLoanIdAndStatusIn(any(), any())).thenReturn(true);
        assertThat(facts(loan(TODAY.minusDays(1))).ineligibleReason()).isEqualTo("DEBIT_IN_FLIGHT");
        when(debits.existsByLoanIdAndStatusIn(any(), any())).thenReturn(false);

        // Phase 6c: no extension while an EMI conversion is open on the loan.
        when(emis.existsByLoanIdAndStatusIn(any(), any())).thenReturn(true);
        assertThat(facts(loan(TODAY.minusDays(1))).ineligibleReason()).isEqualTo("EMI_OPEN");
        when(emis.existsByLoanIdAndStatusIn(any(), any())).thenReturn(false);

        Loan capped = loan(TODAY.minusDays(1));
        capped.setExtensionCount(3);
        ExtensionFacts f = facts(capped);
        assertThat(f.ineligibleReason()).isEqualTo("EXTENSION_CAP_REACHED");
        assertThat(f.cap()).isEqualTo(3);

        Loan closed = loan(TODAY.minusDays(1));
        closed.moveTo(LoanLifecycle.CLOSED);
        assertThat(facts(closed).ineligibleReason()).isEqualTo("LOAN_CLOSED");
    }

    /** EXT-16: never extend on a short (or long) payment; only the exact quote for the bank date. */
    @Test
    void ext16_aPaymentThatIsNotTheQuoteNeverExtends() {
        Loan loan = loan(TODAY.minusDays(3));
        UUID id = UUID.randomUUID();
        when(extensions.find(id)).thenReturn(Optional.of(ref(id, "ACCEPTED")));
        assertThatThrownBy(() -> ledger.requirePayable(loan, id, 25_800L, TODAY))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("code", "EXTENSION_AMOUNT_MISMATCH");
        ledger.requirePayable(loan, id, 31_800L, TODAY);
        verify(debits, org.mockito.Mockito.atLeastOnce()).lockLoan(anyLong());
    }

    /** EXT-19 / EXT-09: only an ACCEPTED, unexpired extension takes a payment; else 409 with the state. */
    @Test
    void ext09_aPaymentForAnExtensionNotWaitingForOneIs409() {
        Loan loan = loan(TODAY.minusDays(3));
        UUID id = UUID.randomUUID();
        when(extensions.find(id)).thenReturn(Optional.of(ref(id, "APPROVED")));
        assertThatThrownBy(() -> ledger.requirePayable(loan, id, 31_800L, TODAY))
                .hasFieldOrPropertyWithValue("code", "STALE_STATE")
                .hasFieldOrPropertyWithValue("currentState", "APPROVED");
    }

    /** CC-69: the extension was cancelled because the loan closed; the verified money is refunded, nothing moves. */
    @Test
    void cc69_verifyingAPaymentForACancelledExtensionDoesNotActivate() {
        Loan loan = loan(TODAY.minusDays(3));
        UUID id = UUID.randomUUID();
        when(extensions.find(id)).thenReturn(Optional.of(ref(id, "CANCELLED")));
        Payment p = new Payment();
        p.setId(9L);
        p.setPurpose(PaymentPurpose.EXTENSION);
        p.setExtensionId(id);
        assertThat(ledger.onVerified(loan, p)).isFalse();
        verify(extensions, never()).activated(any(), any(), any());
        assertThat(loan.getDueDate()).isEqualTo(TODAY.minusDays(3));
    }

    private ExtensionFacts facts(Loan loan) {
        lenient().when(loans.findById(1L)).thenReturn(Optional.of(loan));
        return ledger.facts(1L, TODAY);
    }

    private static ExtensionRef ref(UUID id, String status) {
        Instant expires = TODAY.atTime(23, 59, 59).atZone(IST).toInstant();
        return new ExtensionRef(id, 1L, 1, status, 0L, 10_000L, 1_800L, 14_000L, TODAY, expires,
                TODAY.minusDays(3), TODAY.plusDays(11), 21L, 22L, Instant.EPOCH, null, null, null, null, null, null);
    }

    /** ₹1,000 on the DhanNow v1 terms, due on {@code due}. */
    private static Loan loan(LocalDate due) {
        Loan loan = new Loan();
        loan.setId(1L);
        loan.setCustomerId(7L);
        loan.setPrincipal(100_000L);
        loan.setDisbursedOn(due.minusDays(14));
        loan.setDueDate(due);
        loan.setOriginalDueDate(due);
        loan.setDueAt(LoanMath.dueAt(due, com.navix.loan.domain.ProductSnapshot.SEED));
        loan.setGraceDays(0);
        loan.setPricingVersionId(1L);
        loan.setProductVersionId(1L);
        loan.setTotalRepayable(100_000L);
        loan.setOutstanding(100_000L);
        loan.setStatus(LoanStatus.ACTIVE);
        return loan;
    }

    private static PricingService dhanNowPricing() {
        PricingConfigRepository pricingRepo = mock(PricingConfigRepository.class);
        ProductConfigRepository productRepo = mock(ProductConfigRepository.class);
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(
                TestPricing.pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE)));
        when(productRepo.findById(1L)).thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        return new PricingService(pricingRepo, productRepo);
    }
}
