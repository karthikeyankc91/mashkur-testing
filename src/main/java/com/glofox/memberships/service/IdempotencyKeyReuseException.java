package com.glofox.memberships.service;

/** This idempotency key was already used for a different payload. */
public final class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("This Idempotency-Key was already used with a different request body");
    }
}
