package com.navix.loan.service;

import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.collections.ExtensionDirectory.ExtensionRef;
import com.navix.loan.dto.LoanDtos.ExtensionLine;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.LoanDtos.PendingExtension;
import com.navix.loan.entity.Loan;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/loan/{id}} gains {@code pendingExtension} and {@code extensions[]} (PLAN Phase 6b, item 7).
 * An offer past its pay-by reads as EXPIRED even before the sweep runs.
 */
@Service
@RequiredArgsConstructor
public class LoanExtensionView {

    private final ExtensionDirectory extensions;
    private final LoanExtensionLedger ledger;
    private final PricingService pricing;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LoanView attach(Loan loan, LoanView view) {
        List<ExtensionRef> history = extensions.borrowerHistory(loan.getId());
        ExtensionRef open = history.stream()
                .filter(ExtensionRef::borrowerPending)
                .filter(e -> "PAYMENT_PENDING".equals(e.status()) || !clock.instant().isAfter(e.expiresAt()))
                .reduce((a, b) -> b).orElse(null);
        PendingExtension pending = open == null ? null : new PendingExtension(open.id(), open.status(), open.version(),
                open.seq(), pricing.product(loan.getProductVersionId()).extensionMax(),
                ledger.quote(loan, LocalDate.now(clock)), open.payBy(), open.expiresAt(), open.newDueDate(),
                open.seenByBorrowerAt() != null);
        List<ExtensionLine> lines = history.stream()
                .map(e -> new ExtensionLine(e.id(), e.seq(), displayStatus(e), e.oldDueDate(), e.newDueDate(),
                        e.activatedAt(), e.paymentId()))
                .toList();
        return view.withExtensions(pending, lines);
    }

    private String displayStatus(ExtensionRef e) {
        boolean lapsed = ("APPROVED".equals(e.status()) || "ACCEPTED".equals(e.status()))
                && clock.instant().isAfter(e.expiresAt());
        return lapsed ? "EXPIRED" : e.status();
    }
}
