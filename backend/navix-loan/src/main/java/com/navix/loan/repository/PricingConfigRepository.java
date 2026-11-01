package com.navix.loan.repository;

import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.entity.PricingConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Versioned pricing (V92). At most one row is ACTIVE (partial unique index). */
@Repository
public interface PricingConfigRepository extends JpaRepository<PricingConfig, Long> {

    Optional<PricingConfig> findFirstByStatus(ConfigStatus status);
}
