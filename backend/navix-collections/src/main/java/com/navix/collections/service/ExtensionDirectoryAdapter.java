package com.navix.collections.service;

import com.navix.collections.entity.ExtensionStatus;
import com.navix.collections.entity.LoanExtension;
import com.navix.collections.repository.LoanExtensionRepository;
import com.navix.common.collections.ExtensionDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collections side of {@link ExtensionDirectory}: the loan module reads extensions and moves them through
 * the payment states. Repository only (no {@code LoanDirectory}), so the two modules form no bean cycle.
 * The caller holds the loan advisory lock.
 */
@Component
@RequiredArgsConstructor
public class ExtensionDirectoryAdapter implements ExtensionDirectory {

    private final LoanExtensionRepository extensions;

    @Override
    @Transactional(readOnly = true)
    public Optional<ExtensionRef> find(UUID extensionId) {
        return extensionId == null ? Optional.empty() : extensions.findById(extensionId).map(ExtensionDirectoryAdapter::ref);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExtensionRef> borrowerHistory(Long loanId) {
        return extensions.findByLoanIdOrderBySeqAsc(loanId).stream()
                .filter(e -> e.getApprovedAt() != null)
                .map(ExtensionDirectoryAdapter::ref)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpen(Long loanId) {
        return extensions.findFirstByLoanIdAndStatusIn(loanId, ExtensionStatus.OPEN).isPresent();
    }

    @Override
    @Transactional
    public void paymentPending(UUID extensionId) {
        require(extensionId).moveTo(ExtensionStatus.PAYMENT_PENDING);
    }

    @Override
    @Transactional
    public void paymentRejected(UUID extensionId) {
        LoanExtension e = require(extensionId);
        if (e.getStatus() == ExtensionStatus.PAYMENT_PENDING) {
            e.moveTo(ExtensionStatus.ACCEPTED);
        }
    }

    @Override
    @Transactional
    public void activated(UUID extensionId, Long paymentId, Instant at) {
        LoanExtension e = require(extensionId);
        e.moveTo(ExtensionStatus.ACTIVE);
        e.setPaymentId(paymentId);
        e.setActivatedAt(at);
        extensions.saveAndFlush(e);
    }

    @Override
    @Transactional
    public void cancelOpen(Long loanId, String reason, Instant at) {
        extensions.findFirstByLoanIdAndStatusIn(loanId, ExtensionStatus.OPEN).ifPresent(e -> {
            e.moveTo(ExtensionStatus.CANCELLED);
            e.setCancelReason(reason);
            e.setCancelledAt(at);
        });
    }

    private LoanExtension require(UUID id) {
        return extensions.findById(id)
                .orElseThrow(() -> new com.navix.common.exception.ResourceNotFoundException("LoanExtension", String.valueOf(id)));
    }

    static ExtensionRef ref(LoanExtension e) {
        return new ExtensionRef(e.getId(), e.getLoanId(), e.getSeq(), e.getStatus().name(), e.getVersion(),
                e.getFeePaise(), e.getGstPaise(), e.getInterestPaise(), e.getPayBy(), e.getExpiresAt(),
                e.getOldDueDate(), e.getNewDueDate(), e.getProposedBy(), e.getApprovedBy(), e.getApprovedAt(),
                e.getAcceptedAt(), e.getDeclinedAt(), e.getDeclinedReason(), e.getSeenByBorrowerAt(),
                e.getPaymentId(), e.getActivatedAt());
    }
}
