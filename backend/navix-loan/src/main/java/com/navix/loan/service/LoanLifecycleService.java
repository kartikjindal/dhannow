package com.navix.loan.service;

import com.navix.common.loan.LoanDirectory;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a loan along its timed lifecycle (PLAN Phase 3): ACTIVE → DUE_TODAY at 00:00 IST on the due
 * date → OVERDUE_D1 after {@code due_at} → OVERDUE_REVIEW at the product's {@code overdue_review_dpd}.
 * The target is derived from the clock against the due instant, so a missed run catches up (CC-13);
 * the row lock makes concurrent runs move a loan once (CC-14). Driven by {@code LoanLifecycleScheduler}.
 */
@Service
@RequiredArgsConstructor
public class LoanLifecycleService {

    /** The states the clock can still move a loan out of. OVERDUE_REVIEW waits for payment or staff. */
    static final Set<LoanLifecycle> TIMED = EnumSet.of(LoanLifecycle.ACTIVE, LoanLifecycle.DUE_TODAY,
            LoanLifecycle.OVERDUE_D1);

    private final LoanRepository loans;
    private final PricingService pricing;
    private final LoanDirectory loanDirectory;
    private final Clock clock;

    /** Loans that may need a move now: in a timed state and due today or earlier (IST). */
    @Transactional(readOnly = true)
    public List<Long> dueForReview() {
        return loans.findIdsForLifecycleSweep(TIMED, LocalDate.now(clock));
    }

    /** Bring one loan up to the state the clock implies. @return true if its lifecycle changed. */
    @Transactional
    public boolean advance(Long loanId) {
        Loan loan = loans.findByIdForUpdate(loanId).orElse(null);
        if (loan == null || loan.getDueDate() == null || !TIMED.contains(loan.getLifecycleStatus())) {
            return false;
        }
        LoanLifecycle before = loan.getLifecycleStatus();
        ProductSnapshot product = pricing.product(loan.getProductVersionId());
        Instant dueAt = loan.getDueAt() != null ? loan.getDueAt() : LoanMath.dueAt(loan.getDueDate(), product);
        LoanLifecycle target = LoanLifecycle.forTime(clock.instant(), dueAt, product.zone(), product.overdueReviewDpd());
        if (target == LoanLifecycle.OVERDUE_REVIEW) {
            // navix's way into collections (status IN_COLLECTIONS), reused so an officer's case and
            // the clock reach review the same way. Same transaction, same locked row.
            loan.advanceTo(LoanLifecycle.OVERDUE_D1);
            loanDirectory.markInCollections(loanId);
        } else {
            loan.advanceTo(target);
        }
        if (loan.getLifecycleStatus() == before) {
            return false;
        }
        loans.save(loan);
        return true;
    }
}
