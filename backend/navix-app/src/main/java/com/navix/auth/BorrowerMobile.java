package com.navix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The mobile → customer id registry (V40; CC-24 since V112). The id is server-generated from {@code customer_id_seq}
 * the first time a number signs in ({@code DN0000001} on screen); the mobile is unique and only an attribute.
 *
 * <p>Distinct from {@code customer_profile.mobile}, which is deliberately non-unique (V23) because a
 * returning borrower has one profile row per application. The grain here is one row per BORROWER.
 */
@Entity
@Table(name = "borrower_mobile")
@Getter
@Setter
@NoArgsConstructor
public class BorrowerMobile {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "mobile", nullable = false, unique = true, length = 10)
    private String mobile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
