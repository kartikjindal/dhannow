package com.navix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.pii.PiiCipher;
import com.navix.common.pii.PiiView;
import com.navix.common.security.ActingRole;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.security.JwtService;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** PLAN Phase 9: no "Act as role" in prod, and a request that showed PII in full is written to the access log. */
class JwtAuthFilterTest {

    private final JwtService jwt = new JwtService("test-only-secret-for-the-filter-unit-test", 3600, 3600);
    private final StaffSessionRegistry sessions = mock(StaffSessionRegistry.class);
    private final PiiAccessLog accessLog = mock(PiiAccessLog.class);

    private MockHttpServletRequest adminRequest() {
        when(sessions.isCurrent(anyString(), any())).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/customers/7");
        req.addHeader("Authorization", "Bearer " + jwt.issue("1", "Admin", "ADMIN", JwtService.AUDIENCE_STAFF, "s1"));
        req.addHeader(ActingRole.HEADER, "CREDIT_HEAD");
        return req;
    }

    private CurrentActor actorSeenBy(JwtAuthFilter filter) throws Exception {
        AtomicReference<CurrentActor> seen = new AtomicReference<>();
        filter.doFilter(adminRequest(), new MockHttpServletResponse(), (rq, rs) -> seen.set(ActorContext.get()));
        return seen.get();
    }

    @Test
    void theActingRoleHeaderIsIgnoredWhereTheSwitcherIsOff() throws Exception {
        assertThat(actorSeenBy(new JwtAuthFilter(jwt, sessions, true, accessLog)).actingRole()).isEqualTo("CREDIT_HEAD");
        CurrentActor prod = actorSeenBy(new JwtAuthFilter(jwt, sessions, false, accessLog));
        assertThat(prod.actingRole()).isNull();
        assertThat(prod.effectiveRole()).isEqualTo("ADMIN");
    }

    @Test
    void aRequestThatShowedPiiInFullIsLoggedOnceWithTheFieldsNotTheValues() throws Exception {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        PiiCipher.install(new PiiCipher(k));
        PiiView.setViewRoles(Set.of("ADMIN"));
        JwtAuthFilter filter = new JwtAuthFilter(jwt, sessions, false, accessLog);

        filter.doFilter(adminRequest(), new MockHttpServletResponse(), (rq, rs) -> {
            PiiView.pan("ABCDE1234F");
            PiiView.aadhaar("123456789012");
            PiiView.pan("ZYXWV9876E");
        });

        verify(accessLog).record(any(CurrentActor.class), eq("GET"), eq("/api/customers/7"), eq(Set.of("aadhaar", "pan")));
        assertThat(PiiView.drainRevealed()).isEmpty();
    }

    @Test
    void nothingIsLoggedWhenNothingWasShownInFull() throws Exception {
        new JwtAuthFilter(jwt, sessions, false, accessLog)
                .doFilter(adminRequest(), new MockHttpServletResponse(), (rq, rs) -> { });
        verify(accessLog, never()).record(any(), anyString(), anyString(), any());
    }
}
