package com.navix.loan.service;

import com.navix.loan.domain.InterestMode;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.domain.ProductSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Pure money-math for the salary-linked single-repayment loan product.
 *
 * <p><b>Money is integer paise everywhere</b> (1 rupee = 100 paise). Every amount in and
 * out of this class is a {@code long} number of paise; there is no floating-point money.
 * Rounding to a whole paisa is always {@link RoundingMode#HALF_UP}. This class is the
 * backend source of truth for loan economics and mirrors
 * {@code frontend/src/lib/calc/loan-math.ts}.
 *
 * <p>Pricing is flat across risk categories. Worked example for a principal of ₹10,000
 * (= 1_000_000 paise):
 * <pre>
 *   processingFee  = 1_000_000 * 0.10          =   100_000   (₹1,000)
 *   gst            =   100_000 * 0.18           =    18_000   (₹180)
 *   netDisbursed   = 1_000_000 - 100_000 - 18_000 = 882_000   (₹8,820)
 *   interest (30d) = 1_000_000 * 0.01 * 30       =   300_000   (₹3,000)
 *   totalRepayable = 1_000_000 + 300_000         = 1_300_000   (₹13,000)
 * </pre>
 * Processing fee + GST are taken up front (they reduce {@code netDisbursed}); they are
 * <i>not</i> part of {@code totalRepayable}, which is principal + accrued interest.
 *
 * <p><b>DhanNow (PLAN Phase 1):</b> every rate comes from a {@link PricingSnapshot}, the
 * {@code pricing_config} version frozen on the offer ({@code PricingService.mathFor}). In
 * {@link InterestMode#UPFRONT} the tenure's interest is deducted at disbursal too and the borrower
 * repays the principal only: ₹1,000 → fee 100 + GST 18 + 14 × 1% interest 140 → ₹742 net, ₹1,000
 * repaid. The static rates below are kept only as seed defaults: the no-arg constructor (the Spring
 * bean, and every row without a frozen version) prices exactly as navix did.
 */
@Service
public class LoanMath {

    /** Up-front processing fee rate: 10% of principal. */
    public static final BigDecimal PROCESSING_FEE_RATE = new BigDecimal("0.10");

    /** GST rate applied on the processing fee: 18%. */
    public static final BigDecimal GST_RATE = new BigDecimal("0.18");

    /** Interest accrued per day: 1% of principal. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.01");

    /** Late penalty per day past due: 2% of principal. */
    public static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.02");

    /** Late penalty accrues for at most this many days, then collections. */
    public static final int LATE_PENALTY_CAP_DAYS = 30;

    /** Instant-loan ceiling: the 25%-of-salary limit never exceeds ₹10,00,000 = 100,000,000 paise. */
    public static final long MAX_INSTANT_LOAN_PAISE = 100_000_000L;

    /** Eligible limit is 25% of monthly salary (before flooring + the instant ceiling). */
    public static final BigDecimal LIMIT_PCT_OF_SALARY = new BigDecimal("0.25");

    /** Eligible limit is rounded down to the nearest ₹100 (= 10,000 paise). */
    public static final long LIMIT_ROUNDING_PAISE = 10_000L;

    /** Maximum loan term: the due date must fall within this many days of disbursement. */
    public static final int MAX_TERM_DAYS = 40;

    /** Legacy/default tenure used only when a salary-derived tenure is unavailable. */
    public static final int TERM_DAYS = 30;

    /**
     * Grace after the due date before any penalty accrues, for loans disbursed <b>from now on</b>:
     * none — the penalty starts the day after the salary day. Stamped onto {@code loan.grace_days} at
     * disbursal; the pricing reads the row, never this constant, so loans disbursed before V91 keep
     * the 1 day they were given.
     */
    public static final int SALARY_GRACE_DAYS = 0;

    /** Floor on a sanctioned advance: ₹1,000 = 100,000 paise. */
    public static final long MIN_LOAN_PAISE = 100_000L;

    /** DhanNow seed tenure: 14 days from the confirmed disbursal date. */
    public static final int DHANNOW_TENURE_DAYS = 14;

    /** The navix terms (interest accrued, salary-linked due date): rows with no frozen version. */
    public static final PricingSnapshot NAVIX_PRICING = new PricingSnapshot(null, 0, PROCESSING_FEE_RATE,
            GST_RATE, DAILY_INTEREST_RATE, TERM_DAYS, InterestMode.ACCRUED, true, LATE_PENALTY_RATE,
            LATE_PENALTY_CAP_DAYS, true);

    /** The DhanNow v1 terms, identical to the V92 seed row (tests and documentation). */
    public static final PricingSnapshot DHANNOW_SEED_PRICING = new PricingSnapshot(null, 1, PROCESSING_FEE_RATE,
            GST_RATE, DAILY_INTEREST_RATE, DHANNOW_TENURE_DAYS, InterestMode.UPFRONT, true, LATE_PENALTY_RATE,
            LATE_PENALTY_CAP_DAYS, false);

    private final PricingSnapshot pricing;

    /** Navix terms ({@link #NAVIX_PRICING}). */
    public LoanMath() {
        this(NAVIX_PRICING);
    }

    public LoanMath(PricingSnapshot pricing) {
        this.pricing = Objects.requireNonNull(pricing, "pricing");
    }

    public PricingSnapshot pricing() {
        return pricing;
    }

    /** Up-front processing fee = round(principal × fee rate). */
    public long processingFeePaise(long principalPaise) {
        return roundPaise(BigDecimal.valueOf(principalPaise).multiply(pricing.pfRate()));
    }

    /** GST on the processing fee = round(fee × GST rate). */
    public long gstPaise(long principalPaise) {
        return roundPaise(BigDecimal.valueOf(processingFeePaise(principalPaise)).multiply(pricing.gstRate()));
    }

    /** Interest deducted at disbursal: the whole tenure's interest in UPFRONT mode, else 0. */
    public long upfrontInterestPaise(long principalPaise) {
        return pricing.upfront() ? interestPaise(principalPaise, pricing.tenureDays()) : 0L;
    }

    /** Net amount credited to the borrower = principal − fee − GST (− upfront interest). */
    public long netDisbursedPaise(long principalPaise) {
        return principalPaise - processingFeePaise(principalPaise) - gstPaise(principalPaise)
                - upfrontInterestPaise(principalPaise);
    }

    /** Interest over {@code days} = round(principal × daily rate × days). Negative days clamp to 0. */
    public long interestPaise(long principalPaise, int days) {
        int d = Math.max(0, days);
        return roundPaise(BigDecimal.valueOf(principalPaise)
                .multiply(pricing.dailyRate())
                .multiply(BigDecimal.valueOf(d)));
    }

    /** Interest still owed after {@code days} held: none in UPFRONT mode (deducted at disbursal). */
    public long accruedInterestPaise(long principalPaise, int days) {
        return pricing.upfront() ? 0L : interestPaise(principalPaise, days);
    }

    /** Total repayable after {@code days} held = principal + accrued interest (fee/GST excluded). */
    public long totalRepayablePaise(long principalPaise, int days) {
        return principalPaise + accruedInterestPaise(principalPaise, days);
    }

    /**
     * Late penalty for {@code daysLate} = round(principal × penal rate × min(daysLate, cap)).
     * Non-positive {@code daysLate}, or a version with the late charge off, yields 0.
     */
    public long latePenaltyPaise(long principalPaise, int daysLate) {
        if (!pricing.penalEnabled()) {
            return 0L;
        }
        int cappedDays = Math.min(Math.max(0, daysLate), pricing.penalCapDays());
        return roundPaise(BigDecimal.valueOf(principalPaise)
                .multiply(pricing.penalRate())
                .multiply(BigDecimal.valueOf(cappedDays)));
    }

    /** The late charge stops after this many days. */
    public int penalCapDays() {
        return pricing.penalCapDays();
    }

    /**
     * Late-charge days owed in the current due cycle as of {@code asOf}: days past {@code dueDate} after the
     * loan's grace, within what is left of the lifetime cap once {@code usedDays} were charged in earlier
     * cycles (PLAN Phase 6b, EXT-26).
     */
    public int chargeablePenalDays(LocalDate dueDate, LocalDate asOf, int graceDays, int usedDays) {
        int late = Math.max(0, daysPastDue(dueDate, asOf) - graceDays);
        return Math.min(late, Math.max(0, pricing.penalCapDays() - usedDays));
    }

    /**
     * Eligible loan limit — <b>25% of monthly salary</b>, floored to the nearest ₹100 and capped at
     * the ₹10,00,000 instant ceiling. Salary drives both the limit and the due date.
     *
     * @param monthlySalaryPaise gross monthly salary in paise
     * @return the sanctionable limit in paise (25% of salary, ₹100-floored, ≤ ₹10,00,000)
     */
    public long eligibleLimitPaise(long monthlySalaryPaise) {
        long quarter = BigDecimal.valueOf(monthlySalaryPaise)
                .multiply(LIMIT_PCT_OF_SALARY)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        long flooredToHundred = Math.floorDiv(quarter, LIMIT_ROUNDING_PAISE) * LIMIT_ROUNDING_PAISE;
        return Math.min(flooredToHundred, MAX_INSTANT_LOAN_PAISE);
    }

    /**
     * Canonical compute-on-read outstanding balance as of some day.
     *
     * <p>{@code outstanding = principal + interest(daysHeld) + penalty(daysLate) − payments}.
     * Prepayment falls out naturally: pass the actual days held so interest is charged only to
     * the day paid. {@code daysLate} is 0 until the due date (the loan's own {@code grace_days} is
     * applied by the caller, {@code RepaymentService.breakdown}).
     *
     * @param principalPaise principal in paise
     * @param daysHeld       whole days from disbursement to the as-of date (interest accrues)
     * @param daysLate       whole days past the due date after grace (penalty accrues, capped)
     * @param paymentsPaise  sum of verified payments already made, in paise
     * @return remaining amount owed in paise (never negative)
     */
    public long outstandingPaise(long principalPaise, int daysHeld, int daysLate, long paymentsPaise) {
        long gross = totalRepayablePaise(principalPaise, daysHeld)
                + latePenaltyPaise(principalPaise, daysLate);
        return Math.max(0L, gross - paymentsPaise);
    }

    /**
     * Whole days a loan is past due as of {@code asOf} (0 before/on the due date).
     * Bucketing into DPD buckets lives in the collections module.
     */
    public int daysPastDue(LocalDate dueDate, LocalDate asOf) {
        long days = dueDate.until(asOf, java.time.temporal.ChronoUnit.DAYS);
        return (int) Math.max(0L, days);
    }

    /**
     * The due date of a loan disbursed on {@code disbursedOn}: disbursal + tenure in UPFRONT mode (no
     * holiday shift), else the navix salary-linked date ({@link #dueDateFromSalary}; day 1 when the
     * salary day is unknown).
     */
    public LocalDate dueDate(LocalDate disbursedOn, Integer salaryCreditDay) {
        if (pricing.upfront()) {
            return disbursedOn.plusDays(pricing.tenureDays());
        }
        return dueDateFromSalary(disbursedOn, salaryCreditDay != null ? salaryCreditDay : 1);
    }

    /** The instant a due day ends: {@code dueDate} at the product cutoff (23:59:59) in its zone (IST). */
    public static Instant dueAt(LocalDate dueDate, ProductSnapshot product) {
        return dueDate.atTime(product.dueCutoff()).atZone(product.zone()).toInstant();
    }

    /**
     * Single-repayment due date: the <b>latest</b> salary-credit date that is strictly after
     * disbursement and falls within {@link #MAX_TERM_DAYS} days of it — i.e. the last salary the
     * borrower receives inside the 40-day window (maximising tenure up to 40 days).
     *
     * <p>The salary day is clamped to each month's length (e.g. day 31 → 30 Apr, 28/29 Feb).
     * Within any 40-day window there is always at least one monthly salary date.
     *
     * @param disbursedOn     date of disbursement
     * @param salaryCreditDay day of month salary is credited (1–31)
     * @return the computed due date
     */
    public LocalDate dueDateFromSalary(LocalDate disbursedOn, int salaryCreditDay) {
        LocalDate windowEnd = disbursedOn.plusDays(MAX_TERM_DAYS);
        LocalDate best = null;
        // Walk salary dates from the disbursement month through the window-end month.
        for (LocalDate month = disbursedOn.withDayOfMonth(1);
                !month.isAfter(windowEnd.withDayOfMonth(1));
                month = month.plusMonths(1)) {
            LocalDate salaryDate = salaryDateInMonth(month, salaryCreditDay);
            if (salaryDate.isAfter(disbursedOn) && !salaryDate.isAfter(windowEnd)) {
                if (best == null || salaryDate.isAfter(best)) {
                    best = salaryDate;
                }
            }
        }
        // Fallback (should not happen for a monthly salary, but keep it total): the first salary
        // strictly after disbursement, even if it exceeds the window.
        if (best == null) {
            LocalDate salaryThisMonth = salaryDateInMonth(disbursedOn, salaryCreditDay);
            best = salaryThisMonth.isAfter(disbursedOn)
                    ? salaryThisMonth
                    : salaryDateInMonth(disbursedOn.plusMonths(1), salaryCreditDay);
        }
        return best;
    }

    /** Clamp a salary day-of-month to a valid date within the month of {@code anyDayInMonth}. */
    private static LocalDate salaryDateInMonth(LocalDate anyDayInMonth, int salaryCreditDay) {
        int lastDay = anyDayInMonth.lengthOfMonth();
        int day = Math.min(Math.max(1, salaryCreditDay), lastDay);
        return anyDayInMonth.withDayOfMonth(day);
    }

    /** Round fractional paise to a whole paisa, HALF_UP. */
    private static long roundPaise(BigDecimal paise) {
        return paise.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
