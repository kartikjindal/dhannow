package com.navix.common.brand;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The lender of record ({@code navix.lender.*}), named on every loan document. The defaults are
 * {@code [placeholder]} values; the prod config guard refuses to boot until every one is real.
 *
 * @param registration the lender's regulatory registration, as the lender states it
 * @param contact      the lender's own support and grievance contact
 */
@ConfigurationProperties(prefix = "navix.lender")
public record LenderProperties(String legalName, String address, String registration, String contact) {

    /** The property names still blank or a {@code [placeholder]}; empty when the lender is configured. */
    public List<String> unset() {
        List<String> unset = new ArrayList<>();
        check(unset, "legal-name", legalName);
        check(unset, "address", address);
        check(unset, "registration", registration);
        check(unset, "contact", contact);
        return unset;
    }

    private static void check(List<String> unset, String name, String value) {
        if (value == null || value.isBlank() || value.contains("[")) {
            unset.add("navix.lender." + name);
        }
    }
}
