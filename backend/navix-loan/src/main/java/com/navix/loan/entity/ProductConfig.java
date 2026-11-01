package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.ProductSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One version of the product rules (V93): the credit-ladder amounts and when a due day ends. */
@Entity
@Table(name = "product_config")
@Getter
@Setter
@NoArgsConstructor
public class ProductConfig extends BaseAuditEntity {

    @Column(name = "version", nullable = false)
    private Integer version;

    /** Ascending; level n of the credit ladder is the n-th amount. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_amounts_paise", nullable = false)
    private Long[] allowedAmountsPaise;

    @Column(name = "min_paise", nullable = false)
    private Long minPaise;

    @Column(name = "max_paise", nullable = false)
    private Long maxPaise;

    @Column(name = "due_cutoff_time", nullable = false)
    private LocalTime dueCutoffTime;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "one_live_loan", nullable = false)
    private boolean oneLiveLoan;

    @Column(name = "late_blocks_upgrade_days", nullable = false)
    private Integer lateBlocksUpgradeDays;

    /** Days past due at which an overdue loan moves to OVERDUE_REVIEW (V97). */
    @Column(name = "overdue_review_dpd", nullable = false)
    private Integer overdueReviewDpd;

    /** Days an offer stays open before the next KFS re-quotes it (V100, CC-02). */
    @Column(name = "offer_validity_days", nullable = false)
    private int offerValidityDays = 3;

    /** Loan extensions allowed per loan before an ADMIN override is needed (V103). */
    @Column(name = "extension_max", nullable = false)
    private int extensionMax = 3;

    /** Days between recovery-EMI instalments (V104). */
    @Column(name = "emi_gap_days", nullable = false)
    private int emiGapDays = 10;

    /** Principal from which a recovery EMI has three instalments instead of two (V104). */
    @Column(name = "emi_three_from_paise", nullable = false)
    private long emiThreeFromPaise = 700_000L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ConfigStatus status;

    public ProductSnapshot toSnapshot() {
        return new ProductSnapshot(getId(), Arrays.asList(allowedAmountsPaise), minPaise, maxPaise,
                dueCutoffTime, ZoneId.of(timezone), overdueReviewDpd, offerValidityDays,
                extensionMax, emiGapDays, emiThreeFromPaise);
    }
}
