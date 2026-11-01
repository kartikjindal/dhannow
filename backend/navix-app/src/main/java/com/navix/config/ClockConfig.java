package com.navix.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one app-wide {@link Clock} (PLAN Phase 1), in IST: the product's dates (disbursal day 0, due at
 * 23:59:59 IST) are Indian calendar dates while ECS runs UTC. Tests pin time by passing a fixed Clock.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock clock() {
        return Clock.system(IST);
    }
}
