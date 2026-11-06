package com.navix.loan.repository;

import com.navix.loan.entity.OnboardingWaitlist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingWaitlistRepository extends JpaRepository<OnboardingWaitlist, Long> {

    Optional<OnboardingWaitlist> findByCustomerId(Long customerId);

    // PAN is encrypted (V111): matched on its blind index. Only Aadhaar's last four is kept (Phase 11), never matched.
    default boolean existsByPanAndCustomerIdNot(String pan, Long customerId) {
        return existsByPanHashAndCustomerIdNot(com.navix.common.pii.PiiCipher.panIndex(pan), customerId);
    }

    boolean existsByPanHashAndCustomerIdNot(String panHash, Long customerId);

    List<OnboardingWaitlist> findAllByOrderByCreatedAtDesc();
}
