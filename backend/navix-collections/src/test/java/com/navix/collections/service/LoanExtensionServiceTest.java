package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.dto.ExtensionDtos.AcceptRequest;
import com.navix.collections.dto.ExtensionDtos.OfferRequest;
import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.ExtensionStatus;
import com.navix.collections.entity.LoanExtension;
import com.navix.collections.entity.Settlement;
import com.navix.collections.entity.SettlementStatus;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.LoanExtensionRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.extension.ExtensionFacts;
import com.navix.common.extension.ExtensionQuote;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.loan.LoanSummary;
import com.navix.common.notification.event.ExtensionApprovedEvent;
import com.navix.common.notification.event.ExtensionDeclinedEvent;
import com.navix.common.notification.event.ExtensionExpiredEvent;
import com.navix.common.notification.event.ExtensionOfferedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.OtpVerifierPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** PLAN Phase 6b: the extension state machine, maker-checker and the borrower's confirmation. */
class LoanExtensionServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).atZone(IST).toInstant(), IST);
    private static final Long LOAN = 10L;
    private static final UUID CASE = UUID.randomUUID();

    private final LoanExtensionRepository extensions = mock(LoanExtensionRepository.class);
    private final CollectionCaseRepository cases = mock(CollectionCaseRepository.class);
    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final CollectionsService collections = mock(CollectionsService.class);
    private final LoanDirectory loans = mock(LoanDirectory.class);
    private final OtpVerifierPort otp = mock(OtpVerifierPort.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final List<Object> events = new ArrayList<>();
    private LoanExtensionService service;

    @BeforeEach
    void setUp() {
        service = new LoanExtensionService(extensions, cases, settlements, collections, loans, otp, flags,
                events::add, CLOCK);
        CollectionCase c = new CollectionCase();
        c.setId(CASE);
        c.setLoanId(LOAN);
        lenient().when(cases.findById(CASE)).thenReturn(Optional.of(c));
        lenient().when(flags.isEnabled(LoanExtensionService.FLAG, false)).thenReturn(true);
        lenient().when(loans.extensionFacts(any(), any())).thenReturn(facts(null, 0));
        lenient().when(extensions.findFirstByLoanIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
        lenient().when(extensions.saveAndFlush(any())).thenAnswer(i -> {
            LoanExtension e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
        lenient().when(loans.findLoan(LOAN)).thenReturn(Optional.of(summary()));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void theStateMachineAllowsOnlyThePlannedMoves() {
        assertThat(ExtensionStatus.OFFERED.canMoveTo(ExtensionStatus.APPROVED)).isTrue();
        assertThat(ExtensionStatus.OFFERED.canMoveTo(ExtensionStatus.ACCEPTED)).isFalse();
        assertThat(ExtensionStatus.ACCEPTED.canMoveTo(ExtensionStatus.PAYMENT_PENDING)).isTrue();
        assertThat(ExtensionStatus.PAYMENT_PENDING.canMoveTo(ExtensionStatus.ACCEPTED)).isTrue();
        assertThat(ExtensionStatus.PAYMENT_PENDING.canMoveTo(ExtensionStatus.EXPIRED)).isFalse();
        assertThat(ExtensionStatus.ACTIVE.canMoveTo(ExtensionStatus.CANCELLED)).isFalse();
        LoanExtension e = new LoanExtension();
        assertThatThrownBy(() -> e.moveTo(ExtensionStatus.ACTIVE)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "STALE_STATE")
                .hasFieldOrPropertyWithValue("currentState", "OFFERED");
    }

    @Test
    void anOfficerOffersWithTheQuoteFromTheLoan() {
        asStaff("21", "COLLECTION_EXECUTIVE");
        var v = service.offer(CASE, new OfferRequest(TODAY.plusDays(1), "Borrower asked on call", "REC-1", null));
        assertThat(v.status()).isEqualTo("OFFERED");
        assertThat(v.feePaise() + v.gstPaise() + v.interestPaise()).isEqualTo(25_800L);
        assertThat(v.newDueDate()).isEqualTo(TODAY.minusDays(3).plusDays(14));
        assertThat(v.expiresAt()).isEqualTo(TODAY.plusDays(1).atTime(23, 59, 59).atZone(IST).toInstant());
        assertThat(v.recordingId()).isEqualTo("REC-1");
        assertThat(events).singleElement().isInstanceOf(ExtensionOfferedEvent.class);
        verify(loans).lockLoan(LOAN);
        verify(collections).assertCaseAccessible(CASE);
    }

    /** EXT-01: an offer already open is 409 with it shown; the index decides a true race. */
    @Test
    void ext01_aSecondOfferOnTheSameLoanIs409() {
        asStaff("21", "COLLECTION_EXECUTIVE");
        LoanExtension open = extension(ExtensionStatus.OFFERED);
        when(extensions.findFirstByLoanIdAndStatusIn(any(), any())).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> service.offer(CASE, null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "EXTENSION_ALREADY_OPEN");

        when(extensions.findFirstByLoanIdAndStatusIn(any(), any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uq_loan_extension_open"))
                .when(extensions).saveAndFlush(any());
        assertThatThrownBy(() -> service.offer(CASE, null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "EXTENSION_ALREADY_OPEN");
    }

    /** EXT-02: the loan's reason code comes back as the error; an open settlement blocks too. */
    @Test
    void ext02_anIneligibleLoanIsRefusedWithItsReason() {
        asStaff("21", "COLLECTION_EXECUTIVE");
        when(loans.extensionFacts(any(), any())).thenReturn(facts("NOT_DUE_YET", 0));
        assertThatThrownBy(() -> service.offer(CASE, null)).hasFieldOrPropertyWithValue("code", "NOT_DUE_YET");

        when(loans.extensionFacts(any(), any())).thenReturn(facts(null, 0));
        Settlement s = new Settlement();
        s.setStatus(SettlementStatus.PROPOSED);
        when(settlements.findByCollectionCaseId(CASE)).thenReturn(List.of(s));
        assertThatThrownBy(() -> service.offer(CASE, null)).hasFieldOrPropertyWithValue("code", "SETTLEMENT_OPEN");
        assertThat(service.panel(CASE).reasonCode()).isEqualTo("SETTLEMENT_OPEN");
    }

    /** EXT-03: an officer not assigned to the case gets the existing CASE_NOT_ASSIGNED. */
    @Test
    void ext03_anOfficerNotOnTheCaseCannotOffer() {
        asStaff("21", "COLLECTION_EXECUTIVE");
        org.mockito.Mockito.doThrow(new BusinessException("CASE_NOT_ASSIGNED", "This case is not assigned to you"))
                .when(collections).assertCaseAccessible(CASE);
        assertThatThrownBy(() -> service.offer(CASE, null)).hasFieldOrPropertyWithValue("code", "CASE_NOT_ASSIGNED");
    }

    /** EXT-04: a Head cannot approve their own offer. */
    @Test
    void ext04_theHeadCannotApproveTheirOwnOffer() {
        LoanExtension e = extension(ExtensionStatus.OFFERED);
        e.setProposedBy(31L);
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        asStaff("31", "COLLECTION_HEAD");
        assertThatThrownBy(() -> service.approve(e.getId())).hasFieldOrPropertyWithValue("code", "SOD_VIOLATION");
        asStaff("32", "COLLECTION_HEAD");
        assertThat(service.approve(e.getId()).status()).isEqualTo("APPROVED");
        assertThat(events).singleElement().isInstanceOf(ExtensionApprovedEvent.class);
    }

    /** EXT-05: after the pay-by day the Head cannot approve; the officer offers again. */
    @Test
    void ext05_approvalAfterThePayByDayIsRefused() {
        LoanExtension e = extension(ExtensionStatus.OFFERED);
        e.setExpiresAt(CLOCK.instant().minusSeconds(1));
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        asStaff("32", "COLLECTION_HEAD");
        assertThatThrownBy(() -> service.approve(e.getId())).hasFieldOrPropertyWithValue("code", "EXTENSION_EXPIRED");
    }

    /** EXT-25: the 4th needs an ADMIN with a reason; nobody else can pass the cap. */
    @Test
    void ext25_theCapNeedsAnAdminOverrideWithAReason() {
        when(loans.extensionFacts(any(), any())).thenReturn(facts("EXTENSION_CAP_REACHED", 3));
        asStaff("21", "COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.offer(CASE, new OfferRequest(null, null, null, "hardship")))
                .hasFieldOrPropertyWithValue("code", "EXTENSION_CAP_REACHED");
        asStaff("1", "ADMIN");
        assertThatThrownBy(() -> service.offer(CASE, null)).hasFieldOrPropertyWithValue("code", "EXTENSION_CAP_REACHED");
        var v = service.offer(CASE, new OfferRequest(null, null, null, "Medical emergency, call 12 Nov"));
        assertThat(v.overrideReason()).isEqualTo("Medical emergency, call 12 Nov");
    }

    /** CC-29: with the flag off no new offer is made (open ones carry on). */
    @Test
    void cc29_noNewOfferWhileTheFlagIsOff() {
        when(flags.isEnabled(LoanExtensionService.FLAG, false)).thenReturn(false);
        asStaff("21", "COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.offer(CASE, null)).hasFieldOrPropertyWithValue("code", "FEATURE_DISABLED");
    }

    /** EXT-11: a wrong OTP is refused and the offer stays open. */
    @Test
    void ext11_aWrongOtpLeavesTheOfferOpen() {
        LoanExtension e = approved();
        asBorrower();
        when(otp.verify(anyString(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.accept(LOAN, e.getId(), new AcceptRequest("000000", "EXT-V1", null)))
                .hasFieldOrPropertyWithValue("code", "INVALID_OTP");
        assertThat(e.getStatus()).isEqualTo(ExtensionStatus.APPROVED);
    }

    /** EXT-12: accept is idempotent; a retry after the OTP was used returns ACCEPTED without a code. */
    @Test
    void ext12_acceptIsIdempotent() {
        LoanExtension e = approved();
        asBorrower();
        when(otp.verify("9876543210", "123456", OtpVerifierPort.LOAN_EXTENSION)).thenReturn(true);
        assertThat(service.accept(LOAN, e.getId(), new AcceptRequest("123456", "EXT-V1", 0L)).status())
                .isEqualTo("ACCEPTED");
        assertThat(service.accept(LOAN, e.getId(), new AcceptRequest(null, null, null)).status()).isEqualTo("ACCEPTED");
        verify(otp, org.mockito.Mockito.times(1)).verify(any(), any(), any());
        assertThat(e.getConsentVersion()).isEqualTo("EXT-V1");
    }

    /** EXT-09: a stale screen (old version) gets 409 with the current state. */
    @Test
    void ext09_aStaleVersionIs409() {
        LoanExtension e = approved();
        e.setVersion(3);
        asBorrower();
        assertThatThrownBy(() -> service.accept(LOAN, e.getId(), new AcceptRequest("123456", "EXT-V1", 2L)))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "STALE_STATE");
    }

    /** Another borrower's loan is refused. */
    @Test
    void aBorrowerCannotActOnSomeoneElsesExtension() {
        LoanExtension e = approved();
        ActorContext.set(new CurrentActor("99", "Someone", "BORROWER"));
        assertThatThrownBy(() -> service.seen(LOAN, e.getId())).hasFieldOrPropertyWithValue("code", "FORBIDDEN_OWNER");
    }

    /** EXT-13 / CC-71: declining records the reason and tells the officer; "I didn't ask" is kept for the complaint. */
    @Test
    void cc71_didNotAskDeclinesAndTellsTheOfficer() {
        LoanExtension e = approved();
        e.setCallNote("Borrower asked for 14 days on call");
        asBorrower();
        var v = service.decline(LOAN, e.getId(), "DID_NOT_ASK", null);
        assertThat(v.status()).isEqualTo("DECLINED");
        assertThat(e.getDeclinedReason()).isEqualTo("DID_NOT_ASK");
        assertThat(events).singleElement().isInstanceOfSatisfying(ExtensionDeclinedEvent.class,
                d -> assertThat(d.officerId()).isEqualTo(21L));
        assertThat(e.getCallNote()).isEqualTo("Borrower asked for 14 days on call");
    }

    /** CC-67: confirmed but never paid → EXPIRED after pay-by; the officer is told. */
    @Test
    void cc67_anAcceptedOfferNeverPaidExpires() {
        LoanExtension e = extension(ExtensionStatus.ACCEPTED);
        e.setExpiresAt(CLOCK.instant().minusSeconds(1));
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        assertThat(service.expire(e.getId())).isTrue();
        assertThat(e.getStatus()).isEqualTo(ExtensionStatus.EXPIRED);
        assertThat(events).singleElement().isInstanceOf(ExtensionExpiredEvent.class);
        assertThat(service.expire(e.getId())).isFalse(); // idempotent
    }

    /** EXT-21: the sweep never expires an offer whose payment is waiting for the Accountant. */
    @Test
    void ext21_theSweepSkipsAPendingPayment() {
        LoanExtension e = extension(ExtensionStatus.PAYMENT_PENDING);
        e.setExpiresAt(CLOCK.instant().minusSeconds(1));
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        assertThat(service.expire(e.getId())).isFalse();
        assertThat(e.getStatus()).isEqualTo(ExtensionStatus.PAYMENT_PENDING);
    }

    /** EXT-07: Resend is limited to three a day. */
    @Test
    void ext07_resendIsLimitedToThreeADay() {
        LoanExtension e = approved();
        asStaff("21", "COLLECTION_EXECUTIVE");
        for (int i = 0; i < 3; i++) {
            service.resend(e.getId());
        }
        assertThatThrownBy(() -> service.resend(e.getId())).hasFieldOrPropertyWithValue("code", "RESEND_LIMIT");
        assertThat(events).hasSize(3).allMatch(ExtensionApprovedEvent.class::isInstance);
    }

    /** The officer cannot cancel once a payment is waiting for the Accountant. */
    @Test
    void anOfficerCannotCancelWhileAPaymentIsPending() {
        LoanExtension e = extension(ExtensionStatus.PAYMENT_PENDING);
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        asStaff("21", "COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.cancel(e.getId(), null)).isInstanceOf(ConflictException.class);
        verify(extensions, never()).saveAndFlush(any());
    }

    // ---- fixtures -----------------------------------------------------------------------

    private LoanExtension approved() {
        LoanExtension e = extension(ExtensionStatus.OFFERED);
        e.moveTo(ExtensionStatus.APPROVED);
        e.setApprovedBy(32L);
        e.setApprovedAt(CLOCK.instant());
        when(extensions.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    private static LoanExtension extension(ExtensionStatus status) {
        LoanExtension e = new LoanExtension();
        e.setId(UUID.randomUUID());
        e.setLoanId(LOAN);
        e.setCollectionCaseId(CASE);
        e.setSeq(1);
        e.setFeePaise(10_000L);
        e.setGstPaise(1_800L);
        e.setInterestPaise(14_000L);
        e.setPayBy(TODAY);
        e.setExpiresAt(TODAY.atTime(23, 59, 59).atZone(IST).toInstant());
        e.setOldDueDate(TODAY.minusDays(3));
        e.setNewDueDate(TODAY.plusDays(11));
        e.setProposedBy(21L);
        e.setProposedAt(Instant.EPOCH);
        // Walk the legal path so moveTo stays the only writer.
        ExtensionStatus[] path = {ExtensionStatus.APPROVED, ExtensionStatus.ACCEPTED, ExtensionStatus.PAYMENT_PENDING};
        for (ExtensionStatus s : path) {
            if (e.getStatus() == status) {
                break;
            }
            e.moveTo(s);
        }
        return e;
    }

    private static ExtensionFacts facts(String reason, int used) {
        ExtensionQuote q = new ExtensionQuote(10_000L, 1_800L, 14_000L, 6_000L, 3, TODAY);
        return new ExtensionFacts(LOAN, 7L, "OVERDUE_D1", "NONE", TODAY.minusDays(3), TODAY.minusDays(3),
                TODAY.minusDays(3).plusDays(14), used, 3, reason, q);
    }

    private static LoanSummary summary() {
        return new LoanSummary(LOAN, 7L, 100L, "ACTIVE", 100_000L, 74_200L, 100_000L, 106_000L, TODAY.minusDays(17),
                TODAY.minusDays(3), "Asha", "XXXXX1234X", "9876543210", null, null, null, null, false);
    }

    private static void asStaff(String id, String role) {
        ActorContext.set(new CurrentActor(id, "Staff " + id, role));
    }

    private static void asBorrower() {
        ActorContext.set(new CurrentActor("7", "Asha", "BORROWER"));
    }
}
