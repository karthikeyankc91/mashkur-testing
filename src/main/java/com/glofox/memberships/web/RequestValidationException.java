package com.glofox.memberships.web;

import com.glofox.memberships.web.dto.FieldErrorResponse;
import java.util.List;

/**
 * Shape validation failed. Web-layer only: it carries field errors, which are a
 * presentation concern the service knows nothing about.
 */
public final class RequestValidationException extends RuntimeException {

    private final List<FieldErrorResponse> errors;

    public RequestValidationException(List<FieldErrorResponse> errors) {
        super("Request validation failed");
        this.errors = List.copyOf(errors);
    }

    public List<FieldErrorResponse> errors() {
        return errors;
    }
}
