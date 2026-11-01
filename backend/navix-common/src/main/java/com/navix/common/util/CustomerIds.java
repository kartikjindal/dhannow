package com.navix.common.util;

/** The customer id on screen (CC-24): {@code DN} + the server-generated number, seven digits wide. */
public final class CustomerIds {

    private CustomerIds() {}

    /** {@code DN0000001} for 1; null for null. */
    public static String code(Long customerId) {
        return customerId == null ? null : "DN" + String.format("%07d", customerId);
    }
}
