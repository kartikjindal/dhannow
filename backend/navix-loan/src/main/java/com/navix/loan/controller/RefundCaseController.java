package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.service.RefundCaseService;
import com.navix.loan.service.RefundCaseService.RefundCaseView;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The refund register (CC-08): money owed back to borrowers. Accountant and Admin. */
@RestController
@RequestMapping("/api/refund-cases")
@RequiredArgsConstructor
public class RefundCaseController {

    private final RefundCaseService refunds;

    @GetMapping
    public ApiResponse<List<RefundCaseView>> list(@RequestParam(required = false) String status) {
        if (!Set.of("ACCOUNTANT", "ADMIN").contains(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ACCOUNTANT or ADMIN");
        }
        return ApiResponse.ok(refunds.list(status));
    }
}
