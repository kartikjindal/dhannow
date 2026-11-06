package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.CollectionPayment;
import com.navix.collections.entity.CollectionPaymentKind;
import com.navix.collections.entity.CollectionPaymentStatus;
import com.navix.collections.entity.ExtensionStatus;
import com.navix.collections.entity.LoanExtension;
import com.navix.collections.repository.CollectionCaseRepository;
import com.navix.collections.repository.CollectionPaymentRepository;
import com.navix.collections.repository.LoanExtensionRepository;
import com.navix.collections.repository.SettlementRepository;
import com.navix.common.extension.ExtensionFacts;
import com.navix.common.extension.ExtensionQuote;
import com.navix.common.loan.LoanDirectory;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PLAN Phase 6b: an officer logs the extension payment; only the Accountant's validation moves the due date. */
class CollectionExtensionPaymentTest {

    private final CollectionPaymentRepository payments = mock(CollectionPaymentRepository.class);
    private final CollectionCaseRepository cases = mock(CollectionCaseRepository.class);
    private final LoanDirectory loans = mock(LoanDirectory.class);
    private final LoanExtensionRepository extensions = mock(LoanExtensionRepository.class);
    private CollectionPaymentService service;
    private final UUID caseId = UUID.randomUUID();
    private LoanExtension ext;

    @BeforeEach
    void setUp() {
        service = new CollectionPaymentService(payments, cases, mock(SettlementRepository.class),
                mock(SettlementService.class), loans, mock(StaffDirectory.class), e -> { },
                mock(CollectionsService.class), extensions);
        CollectionCase c = new CollectionCase();
        c.setId(caseId);
        c.setLoanId(2L);
        when(cases.findById(caseId)).thenReturn(Optional.of(c));
        when(payments.save(any())).thenAnswer(i -> i.getArgument(0));
        ext = new LoanExtension();
        ext.setId(UUID.randomUUID());
        ext.setLoanId(2L);
        ext.setProposedBy(9L);
        ext.setApprovedBy(8L);
        ext.setExpiresAt(Instant.now().plusSeconds(3600));
        ext.moveTo(ExtensionStatus.APPROVED);
        ext.moveTo(ExtensionStatus.ACCEPTED);
        when(extensions.findById(ext.getId())).thenReturn(Optional.of(ext));
        ExtensionQuote q = new ExtensionQuote(10_000L, 1_800L, 14_000L, 6_000L, 3, LocalDate.now());
        when(loans.extensionFacts(eq(2L), any())).thenReturn(new ExtensionFacts(2L, 7L, "OVERDUE_D1", "NONE",
                null, null, null, 0, 3, null, q));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    /** EXT-16 / item 2: a mismatched amount is refused, never silently accepted. */
    @Test
    void ext16_anExtensionPaymentForTheWrongAmountIsRefused() {
        ActorContext.set(new CurrentActor("9", "Officer", "COLLECTION_EXECUTIVE"));
        assertThatThrownBy(() -> service.raise(caseId, CollectionPaymentKind.EXTENSION, 25_800L, null, "UTR1", null,
                null, ext.getId())).hasFieldOrPropertyWithValue("code", "EXTENSION_AMOUNT_MISMATCH");
        assertThat(ext.getStatus()).isEqualTo(ExtensionStatus.ACCEPTED);

        var v = service.raise(caseId, CollectionPaymentKind.EXTENSION, 31_800L, null, "UTR1", null, null, ext.getId());
        assertThat(v.status()).isEqualTo("PENDING_ACCOUNTANT");
        assertThat(v.extensionId()).isEqualTo(ext.getId());
        assertThat(ext.getStatus()).isEqualTo(ExtensionStatus.PAYMENT_PENDING);
    }

    /** Only the Accountant's validation moves the due date, through applyExtension; never creditCollectionPayment. */
    @Test
    void theAccountantsValidationAppliesTheExtension() {
        CollectionPayment p = pendingExtensionPayment();
        ActorContext.set(new CurrentActor("5", "Accountant", "ACCOUNTANT"));
        when(loans.applyExtension(eq(2L), eq(ext.getId()), eq(31_800L), any(), any(), any())).thenReturn(77L);

        var v = service.validate(p.getId(), true, null);

        assertThat(v.status()).isEqualTo("VALIDATED");
        assertThat(v.ledgerPaymentId()).isEqualTo(77L);
        verify(loans).lockLoan(2L);
        verify(loans, never()).creditCollectionPayment(anyLong(), anyLong(), any(), any(), any());
    }

    /** The approver of the extension cannot verify its payment (SoD, ADMIN included). */
    @Test
    void theExtensionApproverCannotValidateItsPayment() {
        CollectionPayment p = pendingExtensionPayment();
        ActorContext.set(new CurrentActor("8", "Head as ADMIN", "ADMIN"));
        assertThatThrownBy(() -> service.validate(p.getId(), true, null))
                .hasFieldOrPropertyWithValue("code", "SOD_VIOLATION");
        verify(loans, never()).applyExtension(any(), any(), anyLong(), any(), any(), any());
    }

    /** EXT-19: a rejected payment puts the extension back to ACCEPTED; the borrower pays again until pay-by. */
    @Test
    void ext19_aRejectedPaymentReturnsTheExtensionToAccepted() {
        CollectionPayment p = pendingExtensionPayment();
        ActorContext.set(new CurrentActor("5", "Accountant", "ACCOUNTANT"));
        service.validate(p.getId(), false, "Not on the statement");
        assertThat(ext.getStatus()).isEqualTo(ExtensionStatus.ACCEPTED);
    }

    private CollectionPayment pendingExtensionPayment() {
        ext.moveTo(ExtensionStatus.PAYMENT_PENDING);
        CollectionPayment p = new CollectionPayment();
        p.setId(UUID.randomUUID());
        p.setCollectionCaseId(caseId);
        p.setLoanId(2L);
        p.setKind(CollectionPaymentKind.EXTENSION);
        p.setExtensionId(ext.getId());
        p.setAmountPaise(31_800L);
        p.setPaidOn(LocalDate.now());
        p.setStatus(CollectionPaymentStatus.PENDING_ACCOUNTANT);
        p.setRaisedBy(9L);
        when(payments.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }
}
