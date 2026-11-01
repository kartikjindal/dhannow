package com.navix.auth;

import com.navix.common.security.BorrowerIdentityPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link BorrowerIdentityPort} backed by {@link BorrowerMobileRepository}, the registry
 * {@link AuthController} keeps at sign-in: a mobile collides when it already signs in to another customer.
 */
@Component
@RequiredArgsConstructor
class BorrowerIdentityAdapter implements BorrowerIdentityPort {

    private final BorrowerMobileRepository mobileRepository;

    @Override
    public boolean wouldCollideWithAnotherCustomer(String mobile, Long customerId) {
        String digits = mobile == null ? "" : mobile.replaceAll("[^0-9]", "");
        String number = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        return mobileRepository.findByMobile(number)
                .map(claim -> !claim.getCustomerId().equals(customerId))
                .orElse(false);
    }
}
