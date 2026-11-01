package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.service.RefundCaseService;
import com.navix.loan.service.RefundCaseService.RefundCaseView;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The refund register (CC-08): money owed back to borrowers. Accountant, Finance and Admin; Compliance reads. */
@RestController
@RequestMapping("/api/refund-cases")
@RequiredArgsConstructor
public class RefundCaseController {

    private final RefundCaseService refunds;

    public record RefundedRequest(String utr, LocalDate refundedOn) {
    }

    public record CancelRequest(String reason) {
    }

    @GetMapping
    public ApiResponse<List<RefundCaseView>> list(@RequestParam(required = false) String status) {
        if (!Set.of("ACCOUNTANT", "FINANCE", "COMPLIANCE", "ADMIN").contains(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT, FINANCE or ADMIN");
        }
        return ApiResponse.ok(refunds.list(status));
    }

    /** The money went back: the refund transfer's UTR and date (default today, IST). */
    @PostMapping("/{id}/refunded")
    public ApiResponse<RefundCaseView> refunded(@PathVariable Long id, @RequestBody RefundedRequest request) {
        return ApiResponse.ok(refunds.markRefunded(id, request.utr(), request.refundedOn(), requireFinance()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<RefundCaseView> cancel(@PathVariable Long id, @RequestBody CancelRequest request) {
        return ApiResponse.ok(refunds.cancel(id, request.reason(), requireFinance()));
    }

    /** @return the acting staff id */
    private static Long requireFinance() {
        var actor = ActorContext.get();
        if (!Set.of("ACCOUNTANT", "FINANCE", "ADMIN").contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT, FINANCE or ADMIN");
        }
        try {
            return Long.valueOf(actor.id());
        } catch (NumberFormatException e) {
            throw new BusinessException("ACTOR_NOT_STAFF", "The acting identity is not a real staff id; sign in as staff");
        }
    }
}
