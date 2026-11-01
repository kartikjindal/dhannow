package com.navix.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support for the app (the notification module owns
 * {@code @EnableAsync}; the bootable app owns {@code @EnableScheduling}).
 *
 * <p>Safe on several instances: each sweep runs under a {@code SchedulerLock} lease (V98), and the
 * lifecycle job also row-locks each loan it moves.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
