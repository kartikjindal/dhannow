package com.navix.loan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The annual percentage rate shown on the KFS, sanction letter and agreement (PLAN Phase 5). A DhanNow
 * loan has two cash flows: the net amount paid out on day 0 and the repayment on day {@code days}.
 * The method is behind this interface so Compliance can switch it ({@code navix.apr.method}).
 */
public interface AprCalculator {

    /** APR in percent, rounded half-up to 2 decimals. */
    BigDecimal aprPercent(long netPaise, long repayPaise, int days);

    enum Method implements AprCalculator {

        /** IRR of −net at t0 and +repay at t+days, annualised by compounding: (1+r)^(365/days) − 1. */
        IRR {
            @Override
            public BigDecimal aprPercent(long netPaise, long repayPaise, int days) {
                double periodRate = (double) repayPaise / netPaise - 1;
                return percent(Math.pow(1 + periodRate, 365.0 / days) - 1);
            }
        },

        /** Simple annualisation: r × 365/days, with r the same period rate. */
        SIMPLE {
            @Override
            public BigDecimal aprPercent(long netPaise, long repayPaise, int days) {
                double periodRate = (double) repayPaise / netPaise - 1;
                return percent(periodRate * 365.0 / days);
            }
        };

        private static BigDecimal percent(double rate) {
            return BigDecimal.valueOf(rate).movePointRight(2).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
