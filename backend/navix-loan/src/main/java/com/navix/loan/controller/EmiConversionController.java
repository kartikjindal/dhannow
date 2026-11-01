package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.domain.EmiStatus;
import com.navix.loan.dto.EmiDtos.AcceptRequest;
import com.navix.loan.dto.EmiDtos.BorrowerEmiView;
import com.navix.loan.dto.EmiDtos.DeclineRequest;
import com.navix.loan.dto.EmiDtos.EmiPanel;
import com.navix.loan.dto.EmiDtos.EmiRequest;
import com.navix.loan.dto.EmiDtos.EmiView;
import com.navix.loan.dto.EmiDtos.OtpSentView;
import com.navix.loan.dto.EmiDtos.ReasonRequest;
import com.navix.loan.service.EmiConversionService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recovery EMI conversion (PLAN Phase 6c). Staff endpoints sit under {@code /api/collections} (the staff-only
 * namespace); the service checks roles: collections requests, ADMIN approves. Borrower endpoints are on their
 * own loan. GET/POST only (the BFF proxy forwards nothing else).
 */
@RestController
@RequiredArgsConstructor
public class EmiConversionController {

    private final EmiConversionService emi;

    // ---- staff ----

    @GetMapping("/api/collections/loans/{loanId}/emi")
    public ApiResponse<EmiPanel> panel(@PathVariable Long loanId) {
        return ApiResponse.ok(emi.panel(loanId));
    }

    @PostMapping("/api/collections/loans/{loanId}/emi")
    public ApiResponse<EmiView> request(@PathVariable Long loanId, @RequestBody(required = false) EmiRequest request) {
        return ApiResponse.ok(emi.request(loanId, request));
    }

    /** ADMIN queue; defaults to REQUESTED. */
    @GetMapping("/api/collections/emi")
    public ApiResponse<List<EmiView>> list(@RequestParam(required = false) EmiStatus status) {
        return ApiResponse.ok(emi.list(status));
    }

    @PostMapping("/api/collections/emi/{id}/approve")
    public ApiResponse<EmiView> approve(@PathVariable UUID id) {
        return ApiResponse.ok(emi.approve(id));
    }

    @PostMapping("/api/collections/emi/{id}/reject")
    public ApiResponse<EmiView> reject(@PathVariable UUID id, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(emi.reject(id, request.reason(), request.note()));
    }

    @PostMapping("/api/collections/emi/{id}/cancel")
    public ApiResponse<EmiView> cancel(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest request) {
        return ApiResponse.ok(emi.cancel(id, request == null ? null : request.reason()));
    }

    // ---- borrower ----

    /** The latest offer shown to the borrower (APPROVED, ACTIVE or ended), or null. */
    @GetMapping("/api/loans/{loanId}/emi")
    public ApiResponse<BorrowerEmiView> mine(@PathVariable Long loanId) {
        return ApiResponse.ok(emi.mine(loanId));
    }

    @PostMapping("/api/loans/{loanId}/emi/{id}/otp")
    public ApiResponse<OtpSentView> otp(@PathVariable Long loanId, @PathVariable UUID id) {
        return ApiResponse.ok(emi.requestOtp(loanId, id));
    }

    @PostMapping("/api/loans/{loanId}/emi/{id}/accept")
    public ApiResponse<BorrowerEmiView> accept(@PathVariable Long loanId, @PathVariable UUID id,
                                               @RequestBody AcceptRequest request) {
        return ApiResponse.ok(emi.accept(loanId, id, request));
    }

    @PostMapping("/api/loans/{loanId}/emi/{id}/decline")
    public ApiResponse<BorrowerEmiView> decline(@PathVariable Long loanId, @PathVariable UUID id,
                                                @RequestBody DeclineRequest request) {
        return ApiResponse.ok(emi.decline(loanId, id, request.reason(), request.version()));
    }
}
