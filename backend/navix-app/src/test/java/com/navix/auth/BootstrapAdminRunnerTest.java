package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.iam.domain.StaffRole;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminRunnerTest {

    @Mock private StaffUserRepository staffRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private BootstrapAdminRunner runner(String email, String password) {
        BootstrapAdminRunner r = new BootstrapAdminRunner(staffRepository, encoder);
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "password", password);
        return r;
    }

    private static StaffUser admin(String hash) {
        StaffUser u = new StaffUser();
        u.setEmail("admin@dhannow.com");
        u.setRole(StaffRole.ADMIN);
        u.setPasswordHash(hash);
        return u;
    }

    @Test
    void setsThePasswordOnlyWhileTheAdminHasNone() {
        StaffUser u = admin(null);
        when(staffRepository.findByEmail("admin@dhannow.com")).thenReturn(Optional.of(u));
        runner("admin@dhannow.com", "Boot@123").bootstrap();
        assertThat(encoder.matches("Boot@123", u.getPasswordHash())).isTrue();
        verify(staffRepository).save(u);
    }

    @Test
    void neverOverwritesAnExistingPassword() {
        StaffUser u = admin("$existing");
        when(staffRepository.findByEmail("admin@dhannow.com")).thenReturn(Optional.of(u));
        runner("admin@dhannow.com", "Boot@123").bootstrap();
        assertThat(u.getPasswordHash()).isEqualTo("$existing");
        verify(staffRepository, never()).save(any());
    }

    @Test
    void doesNothingWithoutBothVariables() {
        runner("", "Boot@123").bootstrap();
        runner("admin@dhannow.com", "").bootstrap();
        verify(staffRepository, never()).findByEmail(any());
    }

    /** PLAN Phase 9: one time only. Once an ADMIN can sign in, the secret creates nothing and changes nothing. */
    @Test
    void refusesToRunAgainOnceAnAdminCanSignIn() {
        when(staffRepository.existsByRoleAndPasswordHashIsNotNull(StaffRole.ADMIN)).thenReturn(true);
        runner("second@dhannow.com", "Boot@123").bootstrap();
        verify(staffRepository, never()).findByEmail(any());
        verify(staffRepository, never()).save(any());
    }
}
