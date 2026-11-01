package com.navix.loan.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.loan.domain.LoanStatus;
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
 * owned by {@code ApplicationFlowService}; this service is invoked at the DISBURSED→ACTIVE step to
 * create the financial loan, computing the economics in paise via {@link LoanMath}.
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

    @Transactional(readOnly = true)
    public Loan getLoan(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(id)));
    }

    /**
     * Create the ACTIVE loan for a disbursed application on its frozen terms. {@code disbursedOn} is
     * day 0; null means today in IST.
     */
    @Transactional
    public Loan disburse(LoanApplication application, LocalDate disbursedOn, String disbursalTxnRef) {
        LocalDate disbursed = disbursedOn != null ? disbursedOn : LocalDate.now(clock);
        long principal = application.getAmountRequested();
        LoanMath math = pricing.mathFor(application);
        PricingSnapshot terms = math.pricing();
        // The credit team's sanction-time repayment date is only indicative: a sanction can be
        // disbursed days later (CC-01), so the due date is always derived from the disbursal date.
        LocalDate dueDate = math.dueDate(disbursed, application.getSalaryCreditDay());
        int tenureDays = (int) ChronoUnit.DAYS.between(disbursed, dueDate);

        Loan loan = new Loan();
        loan.setCustomerId(application.getCustomerId());
        loan.setPrincipal(principal);
        loan.setProcessingFee(math.processingFeePaise(principal));
        loan.setGst(math.gstPaise(principal));
        loan.setNetDisbursed(math.netDisbursedPaise(principal));
        loan.setDailyInterestRate(terms.dailyRate().setScale(4, RoundingMode.UNNECESSARY));
        loan.setDisbursedOn(disbursed);
        loan.setDueDate(dueDate);
        loan.setDueAt(LoanMath.dueAt(dueDate, pricing.product(application.getProductVersionId())));
        loan.setTenureDays(tenureDays);
        loan.setGraceDays(LoanMath.SALARY_GRACE_DAYS);
        loan.setPricingVersionId(application.getPricingVersionId());
        loan.setProductVersionId(application.getProductVersionId());
        loan.setUpfrontInterestPaise(terms.upfront() ? math.upfrontInterestPaise(principal) : null);
        long total = math.totalRepayablePaise(principal, tenureDays);
        loan.setTotalRepayable(total);
        loan.setOutstanding(total);
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDisbursalTxnRef(disbursalTxnRef != null && !disbursalTxnRef.isBlank() ? disbursalTxnRef : null);
        return loanRepository.save(loan);
    }
}
