package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.idempotency.IdempotencyService;
import com.navix.common.web.ApiResponse;
import com.navix.loan.service.PaymentGatewayPort;
import com.navix.loan.service.RepaymentService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment gateway callbacks (PLAN Phase 6a stub). Public like every {@code /api/webhooks/**} route, so it
 * is refused outright while the {@code payment-gateway} flag is OFF (v1) and for a provider with no adapter.
 * A callback is recorded once per provider event id; the payment then waits for an Accountant.
 */
@RestController
@RequestMapping("/api/webhooks/payments")
@RequiredArgsConstructor
public class PaymentWebhookController {

    static final String FLAG = "payment-gateway";

    private final FeatureFlagService flags;
    private final ObjectProvider<PaymentGatewayPort> gateways;
    private final IdempotencyService idempotency;
    private final RepaymentService repayments;

    @PostMapping("/{provider}")
    public ApiResponse<Long> callback(@PathVariable String provider, @RequestHeader Map<String, String> headers,
                                      @RequestBody(required = false) String body) {
        if (!flags.isEnabled(FLAG)) {
            throw new BusinessException("FEATURE_DISABLED", "Payment gateway callbacks are not enabled.");
        }
        PaymentGatewayPort gateway = gateways.orderedStream().filter(g -> g.provider().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new BusinessException("UNKNOWN_PROVIDER", "No payment gateway named " + provider));
        PaymentGatewayPort.GatewayPayment p = gateway.parse(headers, body == null ? "" : body);
        return ApiResponse.ok(idempotency.execute("WEBHOOK_" + gateway.provider(),
                gateway.provider() + ":" + p.eventId(), String.valueOf(body),
                () -> repayments.recordGatewayPayment(p.loanId(), p.amountPaise(), p.utr(), p.paidOn()).getId()));
    }
}
