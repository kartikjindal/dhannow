package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints and reads the disbursed {@link Loan} (money/ledger). The application lifecycle itself is
 * owned by {@code ApplicationFlowService}; this service mints the loan when the Disbursement Head
 * records the transfer and activates it when the Accountant confirms it (PLAN Phase 4), computing the
 * economics in paise via {@link LoanMath}.
 *
 * <p>The economics come from the pricing version frozen on the application at offer time
 * ({@link PricingService#mathFor}), so the KFS the borrower signed and the ledger agree. DhanNow
 * (UPFRONT): due = disbursal + 14 days, repay the principal. Navix rows with no frozen version keep
 * the salary-linked due date (latest salary credit ≤ 40 days out) with interest over the tenure.
 */
@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRepository loanRepository;
    private final PricingService pricing;
    private final Clock clock;
    private final RepaymentScheduleService schedules;

    @Transactional(readOnly = true)
    public Loan getLoan(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(id)));
    }

    /**
     * Mint the loan when the Disbursement Head records the transfer (PLAN Phase 4). It waits in
     * DISBURSEMENT_PENDING, with an indicative schedule from {@code disbursedOn} (null = today in IST),
     * until {@link #confirmDisbursal} fixes day 0.
     */
    @Transactional
    public Loan disburse(LoanApplication application, LocalDate disbursedOn, String disbursalTxnRef) {
        long principal = application.getAmountRequested();
        LoanMath math = pricing.mathFor(application);
        PricingSnapshot terms = math.pricing();

        Loan loan = new Loan();
        loan.setCustomerId(application.getCustomerId());
        loan.setPrincipal(principal);
        loan.setProcessingFee(math.processingFeePaise(principal));
        loan.setGst(math.gstPaise(principal));
        loan.setNetDisbursed(math.netDisbursedPaise(principal));
        loan.setDailyInterestRate(terms.dailyRate().setScale(4, RoundingMode.UNNECESSARY));
        loan.setGraceDays(LoanMath.SALARY_GRACE_DAYS);
        loan.setPricingVersionId(application.getPricingVersionId());
        loan.setProductVersionId(application.getProductVersionId());
        loan.setUpfrontInterestPaise(terms.upfront() ? math.upfrontInterestPaise(principal) : null);
        schedule(loan, application, math, disbursedOn != null ? disbursedOn : LocalDate.now(clock));
        loan.startPending();
        loan.setDisbursalTxnRef(disbursalTxnRef != null && !disbursalTxnRef.isBlank() ? disbursalTxnRef : null);
        return loanRepository.save(loan);
    }

    /**
     * The Accountant confirmed the transfer: day 0 is {@code confirmedOn} (null = today in IST), the
     * due date is fixed from it on the frozen terms (CC-01), and the loan goes ACTIVE, then and only then.
     */
    @Transactional
    public Loan confirmDisbursal(Long loanId, LoanApplication application, LocalDate confirmedOn) {
        Loan loan = getLoan(loanId);
        schedule(loan, application, pricing.mathFor(application),
                confirmedOn != null ? confirmedOn : LocalDate.now(clock));
        loan.moveTo(LoanLifecycle.ACTIVE);
        Loan saved = loanRepository.save(loan);
        schedules.writeOriginal(saved); // v1 BULLET on the confirmed due date, never edited (V102)
        return saved;
    }

    /** The Accountant returned the transfer (wrong UTR, not credited): the unconfirmed loan is dropped. */
    @Transactional
    public void discardPending(Long loanId) {
        Loan loan = getLoan(loanId);
        if (loan.getLifecycleStatus() != LoanLifecycle.DISBURSEMENT_PENDING) {
            throw new BusinessException("ILLEGAL_TRANSITION", "Only an unconfirmed disbursal can be returned");
        }
        loanRepository.delete(loan);
    }

    /**
     * Day 0 and everything that follows from it. The credit team's sanction-time repayment date is only
     * indicative: a sanction can be disbursed days later (CC-01), so the due date always comes from day 0.
     */
    private void schedule(Loan loan, LoanApplication application, LoanMath math, LocalDate day0) {
        LocalDate dueDate = math.dueDate(day0, application.getSalaryCreditDay());
        int tenureDays = (int) ChronoUnit.DAYS.between(day0, dueDate);
        loan.setDisbursedOn(day0);
        loan.setDueDate(dueDate);
        loan.setOriginalDueDate(dueDate);
        loan.setDueAt(LoanMath.dueAt(dueDate, pricing.product(application.getProductVersionId())));
        loan.setTenureDays(tenureDays);
        long total = math.totalRepayablePaise(loan.getPrincipal(), tenureDays);
        loan.setTotalRepayable(total);
        loan.setOutstanding(total);
    }
}
