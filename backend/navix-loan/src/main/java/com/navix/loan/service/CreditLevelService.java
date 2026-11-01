package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.domain.CreditLevelReason;
import com.navix.loan.entity.CreditLevel;
import com.navix.loan.entity.CreditLevelHistory;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.CreditLevelHistoryRepository;
import com.navix.loan.repository.CreditLevelRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The DhanNow credit ladder (PLAN Phase 2). Level n may borrow up to the n-th amount of the active
 * {@code product_config} ladder (₹1k → 2k → 3k → 4k → 5k → 7k → 10k).
 *
 * <ul>
 *   <li>First loan = level 1 (no row reads as level 1; the first disbursal writes it).</li>
 *   <li>A loan closed by full repayment on or before its {@code due_at} moves the customer up one
 *       level, capped at the top. Late by any amount, or closed by settlement: same level.</li>
 *   <li>A manual exception needs ADMIN and a reason code, and is recorded with the approver.</li>
 * </ul>
 * Every decision writes a {@code credit_level_history} row.
 */
@Service
@RequiredArgsConstructor
public class CreditLevelService {

    private final CreditLevelRepository levelRepository;
    private final CreditLevelHistoryRepository historyRepository;
    private final PricingService pricing;
    private final Clock clock;

    /** {@code GET /api/me/credit-level}: the level, its limit, the next step and the whole ladder. */
    public record CreditLevelView(int level, long maxPaise, Long nextLevelPaise, List<Long> ladder) {
    }

    @Transactional(readOnly = true)
    public int levelOf(Long customerId) {
        return levelRepository.findById(customerId).map(l -> (int) l.getLevel()).orElse(1);
    }

    /** The most this customer may borrow now. */
    @Transactional(readOnly = true)
    public long maxPaise(Long customerId) {
        return amountAt(ladder(), levelOf(customerId));
    }

    @Transactional(readOnly = true)
    public CreditLevelView view(Long customerId) {
        List<Long> ladder = ladder();
        int level = Math.min(levelOf(customerId), ladder.size());
        Long next = level < ladder.size() ? ladder.get(level) : null;
        return new CreditLevelView(level, amountAt(ladder, level), next, ladder);
    }

    /** The calling borrower's own level. */
    @Transactional(readOnly = true)
    public CreditLevelView mine() {
        CurrentActor actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "The credit level is borrower-scoped");
        }
        return view(Long.valueOf(actor.id()));
    }

    /**
     * A sanction must be one of the ladder amounts and no more than the customer's level allows. The
     * amount is then locked on the offer: a later level change applies to the next loan (CC-04).
     */
    @Transactional(readOnly = true)
    public void requireSanctionable(Long customerId, long amountPaise) {
        if (!ladder().contains(amountPaise)) {
            throw new BusinessException("AMOUNT_NOT_ALLOWED", "Choose one of the loan amounts on offer");
        }
        if (amountPaise > maxPaise(customerId)) {
            throw new BusinessException("LIMIT_EXCEEDED", "The amount is above this customer's credit level");
        }
    }

    /** First disbursal: record level 1. Later disbursals change nothing. */
    @Transactional
    public void onLoanDisbursed(Long customerId, Long loanId) {
        if (levelRepository.existsById(customerId)) {
            return;
        }
        set(customerId, 1, null, CreditLevelReason.FIRST_LOAN, loanId, null, null);
    }

    /**
     * A loan just closed at {@code closedAt}. Up one level only for a full repayment on or before
     * {@code due_at}; a settlement or any lateness keeps the level. Call once per closure.
     */
    @Transactional
    public void onLoanClosed(Loan loan, Instant closedAt, boolean settled) {
        Long customerId = loan.getCustomerId();
        int from = levelOf(customerId);
        Instant dueAt = loan.getDueAt() != null ? loan.getDueAt()
                : LoanMath.dueAt(loan.getDueDate(), pricing.product(loan.getProductVersionId()));
        boolean extended = loan.getExtensionCount() > 0;
        boolean onTime = !settled && !extended && !closedAt.isAfter(dueAt);
        int to = onTime ? Math.min(from + 1, ladder().size()) : from;
        set(customerId, to, from,
                onTime ? CreditLevelReason.ON_TIME_REPAID
                        : extended ? CreditLevelReason.EXTENDED_NO_CHANGE : CreditLevelReason.LATE_REPAID_NO_CHANGE,
                loan.getId(), null, settled ? "SETTLED" : null);
    }

    /**
     * CC-10: the payment that closed {@code loan} was reversed. The closure's history row is marked reversed (so the
     * loan may close again) and any levels it gave are taken back, with a CLOSURE_REVERSED row.
     */
    @Transactional
    public void onClosureReversed(Loan loan) {
        historyRepository.findFirstByLoanIdAndReasonInAndReversedAtIsNull(loan.getId(), java.util.EnumSet.of(
                CreditLevelReason.ON_TIME_REPAID, CreditLevelReason.LATE_REPAID_NO_CHANGE,
                CreditLevelReason.EXTENDED_NO_CHANGE)).ifPresent(closure -> {
            closure.setReversedAt(clock.instant());
            // Flushed now: a re-closure in the same transaction inserts a new closure row (one live per loan).
            historyRepository.saveAndFlush(closure);
            int from = levelOf(loan.getCustomerId());
            int gained = closure.getFromLevel() == null ? 0 : closure.getToLevel() - closure.getFromLevel();
            set(loan.getCustomerId(), Math.max(1, from - gained), from, CreditLevelReason.CLOSURE_REVERSED,
                    loan.getId(), null, null);
        });
    }

    /** ADMIN only: put a customer on {@code toLevel}, with a reason code for the audit trail. */
    @Transactional
    public CreditLevelView manualException(Long customerId, int toLevel, String reasonCode) {
        CurrentActor actor = ActorContext.get();
        if (!"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "A credit-level exception requires the ADMIN role");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "A reason code is required");
        }
        if (toLevel < 1 || toLevel > ladder().size()) {
            throw new BusinessException("INVALID_LEVEL", "Level must be between 1 and " + ladder().size());
        }
        set(customerId, toLevel, levelOf(customerId), CreditLevelReason.MANUAL_EXCEPTION, null,
                Long.valueOf(actor.id()), reasonCode.trim());
        return view(customerId);
    }

    private void set(Long customerId, int to, Integer from, CreditLevelReason reason, Long loanId,
                     Long approvedBy, String reasonCode) {
        Instant now = clock.instant();
        CreditLevel row = levelRepository.findById(customerId).orElseGet(CreditLevel::new);
        row.setCustomerId(customerId);
        row.setLevel((short) to);
        row.setMaxPaise(amountAt(ladder(), to));
        row.setUpdatedAt(now);
        levelRepository.save(row);

        CreditLevelHistory h = new CreditLevelHistory();
        h.setCustomerId(customerId);
        h.setFromLevel(from != null ? from.shortValue() : null);
        h.setToLevel((short) to);
        h.setReason(reason);
        h.setLoanId(loanId);
        h.setApprovedBy(approvedBy);
        h.setReasonCode(reasonCode);
        h.setAt(now);
        historyRepository.save(h);
    }

    private List<Long> ladder() {
        return pricing.activeProduct().allowedAmountsPaise();
    }

    /** The ladder amount for {@code level}; a level above a shortened ladder reads as its top. */
    private static long amountAt(List<Long> ladder, int level) {
        return ladder.get(Math.min(level, ladder.size()) - 1);
    }
}
