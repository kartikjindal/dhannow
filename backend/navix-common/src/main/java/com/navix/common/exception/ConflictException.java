package com.navix.common.exception;

/**
 * A business rejection that means "someone else changed this first" (HTTP 409): a stale version, an
 * illegal transition from the current state, or an offer already open. {@code currentState} goes back to
 * the caller so it can re-render without guessing (PLAN Phase 6b, CC-21, EXT-09).
 */
public class ConflictException extends BusinessException {

    private final String currentState;

    public ConflictException(String code, String message, String currentState) {
        super(code, message);
        this.currentState = currentState;
    }

    public String getCurrentState() {
        return currentState;
    }
}
