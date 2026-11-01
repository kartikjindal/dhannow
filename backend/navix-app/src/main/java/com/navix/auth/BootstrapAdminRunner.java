package com.navix.auth;

import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first ADMIN on boot from a one-time secret, so no password hash is ever committed in a migration (the
 * repo is public). Set {@code NAVIX_BOOTSTRAP_ADMIN_EMAIL} and {@code NAVIX_BOOTSTRAP_ADMIN_PASSWORD} (cloud: SSM
 * {@code /dhannow/<env>/navix/bootstrap/admin-email} and {@code admin-password}) for the first deploy only.
 *
 * <p>One time (PLAN Phase 9): once any ADMIN can sign in with a password, the runner refuses to run again, whatever
 * the variables say, and logs a warning while they are still set. It never overwrites a password, so a leaked or
 * stale secret cannot take over an account; remove the parameters after the first sign-in.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BootstrapAdminRunner {

    private final StaffUserRepository staffRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${navix.bootstrap.admin-email:}")
    private String email;

    @Value("${navix.bootstrap.admin-password:}")
    private String password;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (staffRepository.existsByRoleAndPasswordHashIsNotNull(StaffRole.ADMIN)) {
            log.warn("Bootstrap admin secret is still set but an ADMIN already exists; ignored. Remove "
                    + "NAVIX_BOOTSTRAP_ADMIN_EMAIL / NAVIX_BOOTSTRAP_ADMIN_PASSWORD.");
            return;
        }
        StaffUser admin = staffRepository.findByEmail(email).orElseGet(() -> {
            StaffUser u = new StaffUser();
            u.setEmail(email);
            u.setName("Admin");
            u.setRole(StaffRole.ADMIN);
            u.setStatus(StaffStatus.ACTIVE);
            return u;
        });
        if (admin.getPasswordHash() != null) {
            return;
        }
        if (admin.getRole() != StaffRole.ADMIN) {
            log.warn("Bootstrap admin {} is not an ADMIN; password not set", email);
            return;
        }
        PasswordPolicy.validate(password);
        admin.setPasswordHash(passwordEncoder.encode(password));
        staffRepository.save(admin);
        log.info("Bootstrap admin {} credentialed", email);
    }
}
