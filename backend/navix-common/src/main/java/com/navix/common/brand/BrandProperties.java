package com.navix.common.brand;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The customer-facing brand ({@code navix.brand.*}): the one place the name, website and support
 * contacts come from (CLAUDE.md "One brand config"). Defaults live in application.yml.
 */
@ConfigurationProperties(prefix = "navix.brand")
public record BrandProperties(String name, String website, String supportPhone, String supportEmail) {
}
