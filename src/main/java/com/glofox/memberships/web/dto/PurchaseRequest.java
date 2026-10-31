package com.glofox.memberships.web.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Incoming purchase request. Wire names are snake_case via the global naming
 * strategy, so {@code memberName} arrives as {@code member_name}.
 *
 * <p>Typed fields rather than Strings: Jackson rejects a malformed UUID or date
 * during binding, before any of our code runs, so those become a 400 without a
 * parsing branch anywhere in the application.
 */
public record PurchaseRequest(String memberName, UUID planId, LocalDate startDate) {
}
