package com.navix.loan.repository;

import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.entity.ProductConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Versioned product rules (V93). At most one row is ACTIVE (partial unique index). */
@Repository
public interface ProductConfigRepository extends JpaRepository<ProductConfig, Long> {

    Optional<ProductConfig> findFirstByStatus(ConfigStatus status);
}
