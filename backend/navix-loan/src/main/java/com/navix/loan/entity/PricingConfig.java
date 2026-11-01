package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.InterestMode;
import com.navix.loan.domain.PricingSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One version of the loan pricing (V92). Its terms are frozen by a DB trigger once it leaves DRAFT;
 * offers and loans reference it by id ({@code pricing_version_id}), never by "the current one".
 */
@Entity
@Table(name = "pricing_config")
@Getter
@Setter
@NoArgsConstructor
public class PricingConfig extends BaseAuditEntity {

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "pf_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal pfRate;

    @Column(name = "gst_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal gstRate;

    @Column(name = "daily_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal dailyRate;

    @Column(name = "tenure_days", nullable = false)
    private Integer tenureDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_mode", nullable = false, length = 16)
    private InterestMode interestMode;

    @Column(name = "penal_enabled", nullable = false)
    private boolean penalEnabled;

    @Column(name = "penal_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal penalRate;

    @Column(name = "penal_cap_days", nullable = false)
    private Integer penalCapDays;

    @Column(name = "prepay_rebate", nullable = false)
    private boolean prepayRebate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConfigStatus status;

    public PricingSnapshot toSnapshot() {
        return new PricingSnapshot(getId(), version, pfRate, gstRate, dailyRate, tenureDays, interestMode,
                penalEnabled, penalRate, penalCapDays, prepayRebate);
    }
}
