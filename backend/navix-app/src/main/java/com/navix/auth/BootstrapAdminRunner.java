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
 * Gives the first ADMIN a password on boot, so no password hash is ever committed in a migration (the
 * repo is public). Set {@code NAVIX_BOOTSTRAP_ADMIN_EMAIL} and {@code NAVIX_BOOTSTRAP_ADMIN_PASSWORD}
 * (SSM in cloud). The password is set only while that account has none, so a later boot never
 * overwrites a password the admin has since changed; the variables can be removed after first login.
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
