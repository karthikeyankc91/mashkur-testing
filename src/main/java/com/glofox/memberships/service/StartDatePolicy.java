package com.glofox.memberships.service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The only thing that compares a date against today.
 *
 * <p>Note what this does <strong>not</strong> expose: there is no
 * {@code today()} or {@code now()}. It holds a {@link Clock} and offers only an
 * assertion over it, so a caller can ask "is this date acceptable?" but never
 * "what time is it?". That is what keeps time out of the business rules — a
 * future expiry rule cannot quietly acquire a clock dependency through this
 * class.
 */
public record StartDatePolicy(Clock clock) {

    public void requireNotPast(LocalDate startDate) {
        if (startDate.isBefore(LocalDate.now(clock))) {
            throw new StartDateInPastException(startDate);
        }
    }
}
