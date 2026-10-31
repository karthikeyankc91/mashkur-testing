package com.glofox.memberships.web.dto;

/**
 * One field-level validation failure.
 *
 * @param field the <strong>wire</strong> name ({@code member_name}), not the
 *              Java name, since that is what the client sent
 */
public record FieldErrorResponse(String field, String message) {
}
