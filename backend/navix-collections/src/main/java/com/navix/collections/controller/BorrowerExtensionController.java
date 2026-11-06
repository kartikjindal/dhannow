package com.navix.collections.controller;

import com.navix.collections.dto.ExtensionDtos.AcceptRequest;
import com.navix.collections.dto.ExtensionDtos.BorrowerExtensionView;
import com.navix.collections.dto.ExtensionDtos.DeclineRequest;
import com.navix.collections.dto.ExtensionDtos.OtpSentView;
import com.navix.collections.service.LoanExtensionService;
import com.navix.common.web.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's side of a loan extension (PLAN Phase 6b, item 7). The offer itself is read from
 * {@code GET /api/loan/{id}} ({@code pendingExtension}); paying it is {@code POST /api/loan/{id}/repayments}
 * with {@code purpose=EXTENSION}. BORROWER on their own loan only.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/extensions/{extensionId}")
@RequiredArgsConstructor
public class BorrowerExtensionController {

    private final LoanExtensionService extensions;

    /** The borrower opened the offer (shown to the officer). */
    @PostMapping("/seen")
    public ApiResponse<BorrowerExtensionView> seen(@PathVariable Long loanId, @PathVariable UUID extensionId) {
        return ApiResponse.ok(extensions.seen(loanId, extensionId));
    }

    /** Send the confirmation OTP to the mobile on file. */
    @PostMapping("/otp")
    public ApiResponse<OtpSentView> otp(@PathVariable Long loanId, @PathVariable UUID extensionId) {
        return ApiResponse.ok(extensions.requestOtp(loanId, extensionId));
    }

    /** Confirm with the OTP; idempotent once accepted. */
    @PostMapping("/accept")
    public ApiResponse<BorrowerExtensionView> accept(@PathVariable Long loanId, @PathVariable UUID extensionId,
                                                     @RequestBody AcceptRequest request) {
        return ApiResponse.ok(extensions.accept(loanId, extensionId, request));
    }

    /** "Not now" (NOT_NOW) or "I didn't ask" (DID_NOT_ASK). */
    @PostMapping("/decline")
    public ApiResponse<BorrowerExtensionView> decline(@PathVariable Long loanId, @PathVariable UUID extensionId,
                                                      @RequestBody DeclineRequest request) {
        return ApiResponse.ok(extensions.decline(loanId, extensionId, request.reason(), request.version()));
    }
}
