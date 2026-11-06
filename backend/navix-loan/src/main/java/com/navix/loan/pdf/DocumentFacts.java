package com.navix.loan.pdf;

import com.navix.common.brand.BrandProperties;
import com.navix.common.brand.GrievanceProperties;
import com.navix.common.brand.LenderProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Everything a DhanNow loan document states (PLAN Phase 5). Built once by {@code LoanDocumentService}
 * from the pricing snapshot frozen on the offer, through {@code LoanMath} and {@code AprCalculator},
 * and handed to every renderer, so the KFS, sanction letter, agreement and schedule can never show
 * different figures (money parity). The renderers only format; they compute nothing.
 *
 * @param version        the version of the document being rendered (set per document)
 * @param loanId         the loan account; null until the Disbursement Head mints the loan
 * @param disbursedOn    the confirmed disbursal date; null on the offer, where {@code dueDate} is indicative
 * @param maxPenalPaise  the late charge at its cap, for the "at most" line
 * @param acceptedAt     when the borrower signed; null until then
 */
public record DocumentFacts(
        long applicationId,
        Long loanId,
        int version,
        LocalDate issuedOn,
        String borrowerName,
        String borrowerMobile,
        String borrowerEmail,
        String borrowerAddress,
        String borrowerPan,
        LenderProperties lender,
        BrandProperties brand,
        GrievanceProperties grievance,
        int pricingVersion,
        long principalPaise,
        BigDecimal pfRate,
        long processingFeePaise,
        BigDecimal gstRate,
        long gstPaise,
        BigDecimal dailyRate,
        int tenureDays,
        boolean interestUpfront,
        long interestPaise,
        long netDisbursedPaise,
        long totalRepayablePaise,
        BigDecimal aprPercent,
        String aprMethod,
        boolean penalEnabled,
        BigDecimal penalRate,
        int penalCapDays,
        long maxPenalPaise,
        boolean prepayRebate,
        LocalDate disbursedOn,
        LocalDate dueDate,
        LocalTime dueCutoff,
        LocalDate offerValidUntil,
        String purpose,
        String disbursalAccountMasked,
        Instant acceptedAt) {

    /** Fee + GST + upfront interest: what is held back from the principal at disbursal. */
    public long totalDeductionsPaise() {
        return processingFeePaise + gstPaise + (interestUpfront ? interestPaise : 0);
    }

    public DocumentFacts withVersion(int v) {
        return new DocumentFacts(applicationId, loanId, v, issuedOn, borrowerName, borrowerMobile,
                borrowerEmail, borrowerAddress, borrowerPan, lender, brand, grievance, pricingVersion,
                principalPaise, pfRate, processingFeePaise, gstRate, gstPaise, dailyRate, tenureDays,
                interestUpfront, interestPaise, netDisbursedPaise, totalRepayablePaise, aprPercent, aprMethod,
                penalEnabled, penalRate, penalCapDays, maxPenalPaise, prepayRebate, disbursedOn, dueDate,
                dueCutoff, offerValidUntil, purpose, disbursalAccountMasked, acceptedAt);
    }

    public DocumentFacts withAcceptedAt(Instant at) {
        return new DocumentFacts(applicationId, loanId, version, issuedOn, borrowerName, borrowerMobile,
                borrowerEmail, borrowerAddress, borrowerPan, lender, brand, grievance, pricingVersion,
                principalPaise, pfRate, processingFeePaise, gstRate, gstPaise, dailyRate, tenureDays,
                interestUpfront, interestPaise, netDisbursedPaise, totalRepayablePaise, aprPercent, aprMethod,
                penalEnabled, penalRate, penalCapDays, maxPenalPaise, prepayRebate, disbursedOn, dueDate,
                dueCutoff, offerValidUntil, purpose, disbursalAccountMasked, at);
    }
}
