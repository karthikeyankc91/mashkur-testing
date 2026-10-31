package com.glofox.memberships.service;

import java.time.LocalDate;

/** The requested start date is before today. */
public final class StartDateInPastException extends RuntimeException {

    private final LocalDate startDate;

    public StartDateInPastException(LocalDate startDate) {
        super("start_date must not be in the past: " + startDate);
        this.startDate = startDate;
    }

    public LocalDate startDate() {
        return startDate;
    }
}
