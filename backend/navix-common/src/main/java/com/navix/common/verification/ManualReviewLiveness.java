package com.navix.common.verification;

import org.springframework.stereotype.Component;

/** v1 liveness: no automated verdict, every guided selfie goes to the staff "Selfie review" queue. */
@Component
public class ManualReviewLiveness implements LivenessPort {

    public static final String PROVIDER = "MANUAL_REVIEW";

    @Override
    public Verdict assess(Frames frames) {
        return new Verdict("REVIEW", PROVIDER, "Thanks. Our team will check your selfie.");
    }
}
