package com.navix.common.verification;

/**
 * Judges the guided selfie (PLAN Phase 8): three frames, looking straight, right and left. v1 is
 * {@link ManualReviewLiveness}, every capture goes to staff; a Digitap or ML Kit check can implement this later.
 */
public interface LivenessPort {

    /** Object keys of the three uploaded frames. */
    record Frames(String straight, String right, String left) {
    }

    /** {@code status} is a verification status: PASS, REVIEW or FAIL. */
    record Verdict(String status, String provider, String message) {
    }

    Verdict assess(Frames frames);
}
