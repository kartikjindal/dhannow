package com.navix.loan.service;

import java.time.LocalDate;
import java.util.Map;

/**
 * A payment gateway's callback (flag {@code payment-gateway}, OFF in v1: payments are manual UPI/bank
 * transfer verified by an Accountant). No implementation ships; a provider adapter implements this and
 * {@code POST /api/webhooks/payments/{provider}} routes to it by {@link #provider()}.
 */
public interface PaymentGatewayPort {

    /** The path segment this adapter answers, e.g. {@code razorpay}. */
    String provider();

    /** Check the signature and read one callback; throw when it does not verify. */
    GatewayPayment parse(Map<String, String> headers, String body);

    /** @param eventId the provider's unique event id: a replay carries the same one */
    record GatewayPayment(String eventId, Long loanId, long amountPaise, String utr, LocalDate paidOn) {
    }
}
