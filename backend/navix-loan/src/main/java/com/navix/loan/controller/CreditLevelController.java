package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.CreditLevelService;
import com.navix.loan.service.CreditLevelService.CreditLevelView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credit ladder (PLAN Phase 2): the borrower reads their own level; an ADMIN sets a manual
 * exception (role and reason code enforced in {@link CreditLevelService}).
 */
@RestController
@RequiredArgsConstructor
public class CreditLevelController {

    private final CreditLevelService creditLevels;

    public record ManualExceptionRequest(int level, @NotBlank String reasonCode) {
    }

    /** {@code {level, maxPaise, nextLevelPaise, ladder[]}} for the calling borrower. */
    @GetMapping("/api/me/credit-level")
    public ApiResponse<CreditLevelView> mine() {
        return ApiResponse.ok(creditLevels.mine());
    }

    @PostMapping("/api/customers/{customerId}/credit-level")
    public ApiResponse<CreditLevelView> manualException(
            @PathVariable Long customerId, @Valid @RequestBody ManualExceptionRequest req) {
        return ApiResponse.ok(creditLevels.manualException(customerId, req.level(), req.reasonCode()));
    }
}
