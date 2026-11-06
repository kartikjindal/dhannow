package com.navix.loan.entity;

import com.navix.loan.domain.LoanDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One issued loan document (PLAN Phase 5, V100): the KFS, sanction letter, signed agreement, schedule
 * and later receipts and addenda. Versioned per application and type; the bytes live in S3 at
 * {@link #s3Key} with their {@link #sha256}. Documents issued on the offer belong to the application
 * ({@link #applicationId}); the loan id is set on documents issued after disbursal.
 */
@Entity
@Table(name = "loan_document")
@Getter
@Setter
@NoArgsConstructor
public class LoanDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "application_id")
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private LoanDocumentType type;

    /** navix column, unused by DhanNow ({@link #s3Key} holds the location). */
    @Column(name = "storage_uri")
    private String storageUri;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "pricing_version_id")
    private Long pricingVersionId;

    /** The principal the document states. */
    @Column(name = "amount_paise")
    private Long amountPaise;

    @Column(name = "s3_key", length = 512)
    private String s3Key;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_ip", length = 64)
    private String acceptedIp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_evidence", columnDefinition = "jsonb")
    private String acceptanceEvidence;
}
