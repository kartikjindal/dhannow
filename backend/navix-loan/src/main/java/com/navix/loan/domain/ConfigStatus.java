package com.navix.loan.domain;

/**
 * Lifecycle of a {@code pricing_config} / {@code product_config} version (V92/V93). Terms are editable
 * only while DRAFT; at most one version is ACTIVE; RETIRED rows stay for the offers and loans that
 * still reference them.
 */
public enum ConfigStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
