package com.navix.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PLAN Phase 10a/11: SMS and email links open the app; the borrower sees DNL loan numbers. */
class AppLinksTest {

    @Test
    void onlyTheThreeKnownTargetsAreNamedEverythingElseOpensTheAppHome() {
        assertThat(AppLinks.open("https://dhannow.com/", "repay")).isEqualTo("https://dhannow.com/app/open?to=repay");
        assertThat(AppLinks.open("https://dhannow.com", "loans")).isEqualTo("https://dhannow.com/app/open?to=loans");
        assertThat(AppLinks.open("https://dhannow.com", "support")).isEqualTo("https://dhannow.com/app/open?to=support");
        assertThat(AppLinks.open("https://dhannow.com", "/loan/mandate")).isEqualTo("https://dhannow.com/app/open");
        assertThat(AppLinks.open("https://dhannow.com", null)).isEqualTo("https://dhannow.com/app/open");
    }

    @Test
    void loanAccountNumbers() {
        assertThat(CustomerIds.loanAccount(123L)).isEqualTo("DNL0000123");
        assertThat(CustomerIds.loanAccount(null)).isNull();
    }
}
