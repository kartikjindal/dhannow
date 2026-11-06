package com.navix.loan.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer's place on the credit ladder (V96). Natural PK on {@code customer_id}, like
 * {@link CustomerLimitOverride}. No row = level 1. Changed only by {@code CreditLevelService}, which
 * writes a {@link CreditLevelHistory} row for every decision.
 */
@Entity
@Table(name = "credit_level")
@Getter
@Setter
@NoArgsConstructor
public class CreditLevel {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "level", nullable = false)
    private Short level;

    /** The ladder amount for {@code level} when it was set, in paise. */
    @Column(name = "max_paise", nullable = false)
    private Long maxPaise;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
