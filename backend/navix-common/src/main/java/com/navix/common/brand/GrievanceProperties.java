package com.navix.common.brand;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The platform's grievance officer ({@code navix.grievance.*}), named on every loan document. */
@ConfigurationProperties(prefix = "navix.grievance")
public record GrievanceProperties(String officerName, String officerPhone, String officerEmail) {
}
