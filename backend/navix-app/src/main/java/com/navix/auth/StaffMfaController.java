package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.auth.AuthDtos.MessageResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** PLAN Phase 7: an ADMIN clears a staff member's lost authenticator (never their own). */
@RestController
public class StaffMfaController {

    private final StaffMfaService mfa;

    public StaffMfaController(StaffMfaService mfa) {
        this.mfa = mfa;
    }

    @PostMapping("/api/admin/staff/{staffId}/mfa/reset")
    public ApiResponse<MessageResponse> reset(@PathVariable Long staffId) {
        var actor = ActorContext.get();
        if (!"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
        if (String.valueOf(staffId).equals(actor.id())) {
            throw new BusinessException("SOD_VIOLATION", "Another administrator must reset your authenticator.");
        }
        return ApiResponse.ok(new MessageResponse(mfa.reset(staffId)
                ? "Authenticator reset. They set it up again at their next sign-in."
                : "No authenticator was set up."));
    }
}
