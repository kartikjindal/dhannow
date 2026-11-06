package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.loan.domain.CreditLevelReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One credit-level decision (V96), including "no change" after a late or settled closure. */
@Entity
@Table(name = "credit_level_history")
@Getter
@Setter
@NoArgsConstructor
public class CreditLevelHistory extends BaseAuditEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Null on the FIRST_LOAN row. */
    @Column(name = "from_level")
    private Short fromLevel;

    @Column(name = "to_level", nullable = false)
    private Short toLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private CreditLevelReason reason;

    @Column(name = "loan_id")
    private Long loanId;

    /** The ADMIN who approved a manual exception. */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** Set when the closure this row recorded was reversed (CC-10, V104), so the loan may close again. */
    @Column(name = "reversed_at")
    private Instant reversedAt;
}
