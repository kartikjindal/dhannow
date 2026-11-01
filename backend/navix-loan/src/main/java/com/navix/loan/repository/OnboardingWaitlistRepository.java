package com.navix.loan.repository;

import com.navix.loan.entity.OnboardingWaitlist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingWaitlistRepository extends JpaRepository<OnboardingWaitlist, Long> {

    Optional<OnboardingWaitlist> findByCustomerId(Long customerId);

    // PAN and Aadhaar are encrypted (V111): matched on their blind indexes.
    default boolean existsByPanAndCustomerIdNot(String pan, Long customerId) {
        return existsByPanHashAndCustomerIdNot(com.navix.common.pii.PiiCipher.panIndex(pan), customerId);
    }

    default boolean existsByAadhaarAndCustomerIdNot(String aadhaar, Long customerId) {
        return existsByAadhaarHashAndCustomerIdNot(com.navix.common.pii.PiiCipher.digitsIndex(aadhaar), customerId);
    }

    boolean existsByPanHashAndCustomerIdNot(String panHash, Long customerId);

    boolean existsByAadhaarHashAndCustomerIdNot(String aadhaarHash, Long customerId);

    List<OnboardingWaitlist> findAllByOrderByCreatedAtDesc();
}
