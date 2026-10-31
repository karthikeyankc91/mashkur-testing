package com.glofox.memberships.service;

/** Another request is mid-flight with this idempotency key. */
public final class IdempotentRequestInProgressException extends RuntimeException {

    public IdempotentRequestInProgressException() {
        super("A request with this Idempotency-Key is already in progress");
    }
}
