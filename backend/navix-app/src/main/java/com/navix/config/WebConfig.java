package com.navix.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS (PLAN Phase 8): the browser origins that may call the API, from config. {@code navix.cors.allowed-origins}
 * (default {@code https://dhannow.com,https://staff.dhannow.com}); outside prod {@code navix.cors.dev-origins}
 * (default {@code http://localhost:3000}) is added. The Android app is not a browser and needs no entry. Applied by
 * {@link SecurityConfig} through {@code http.cors()}, so a preflight is answered before authentication.
 */
@Configuration
public class WebConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${navix.cors.allowed-origins:https://dhannow.com,https://staff.dhannow.com}") String allowed,
            @Value("${navix.cors.dev-origins:http://localhost:3000}") String dev,
            @Value("${NAVIX_ENV:dev}") String env) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins(allowed, dev, env));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    static List<String> origins(String allowed, String dev, String env) {
        Stream<String> all = "prod".equalsIgnoreCase(env) ? split(allowed) : Stream.concat(split(allowed), split(dev));
        return all.distinct().toList();
    }

    private static Stream<String> split(String csv) {
        return csv == null ? Stream.empty() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty());
    }
}
