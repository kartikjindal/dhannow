package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link BorrowerIdentityAdapter} over the {@code borrower_mobile} sign-in registry (CC-24). */
@ExtendWith(MockitoExtension.class)
class BorrowerIdentityAdapterTest {

    @Mock private BorrowerMobileRepository mobileRepository;

    private BorrowerIdentityAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BorrowerIdentityAdapter(mobileRepository);
    }

    private static BorrowerMobile claim(long customerId, String mobile) {
        BorrowerMobile row = new BorrowerMobile();
        row.setCustomerId(customerId);
        row.setMobile(mobile);
        return row;
    }

    @Test
    void noCollisionWhenTheMobileIsUnregistered() {
        when(mobileRepository.findByMobile("9876543210")).thenReturn(Optional.empty());

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isFalse();
    }

    @Test
    void noCollisionWhenTheMobileSignsInToTheSameCustomer() {
        when(mobileRepository.findByMobile("9876543210")).thenReturn(Optional.of(claim(9000001L, "9876543210")));

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isFalse();
    }

    @Test
    void collidesWhenTheMobileSignsInToADifferentCustomer() {
        when(mobileRepository.findByMobile("9876543210")).thenReturn(Optional.of(claim(4200002L, "9876543210")));

        assertThat(adapter.wouldCollideWithAnotherCustomer("9876543210", 9000001L)).isTrue();
    }

    /** CC-24: numbers sharing their last seven digits are different people now; only the same number collides. */
    @Test
    void cc24_aDifferentNumberWithTheSameLastSevenDigitsDoesNotCollide() {
        when(mobileRepository.findByMobile("8006543210")).thenReturn(Optional.empty());

        assertThat(adapter.wouldCollideWithAnotherCustomer("8006543210", 9000001L)).isFalse();
    }

    @Test
    void stripsNonDigitCharactersAndTheCountryCode() {
        when(mobileRepository.findByMobile("9876543210")).thenReturn(Optional.empty());

        assertThat(adapter.wouldCollideWithAnotherCustomer("+91 98765 43210", 9000001L)).isFalse();
    }
}
