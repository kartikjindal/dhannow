package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** CC-45: Settings → Delete account (borrower only). {@code GET} answers null while no request exists. */
@RestController
@RequestMapping("/api/me/account-deletion")
@RequiredArgsConstructor
public class AccountDeletionController {

    private final AccountDeletionService deletions;

    @PostMapping
    public ApiResponse<AccountDeletionService.DeletionView> request() {
        return ApiResponse.ok(deletions.request(borrower()));
    }

    @GetMapping
    public ApiResponse<AccountDeletionService.DeletionView> status() {
        return ApiResponse.ok(deletions.find(borrower()).orElse(null));
    }

    private static long borrower() {
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role()) || actor.id() == null) {
            throw new BusinessException("FORBIDDEN_ROLE", "Borrower action required");
        }
        return Long.parseLong(actor.id());
    }
}
