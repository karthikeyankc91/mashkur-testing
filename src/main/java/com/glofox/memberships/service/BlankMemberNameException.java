package com.glofox.memberships.service;

/**
 * The member name was absent, empty, or only whitespace.
 *
 * <p>Carries no payload deliberately: the offending value is caller-controlled
 * and there is nothing diagnostic about it.
 */
public final class BlankMemberNameException extends RuntimeException {

    public BlankMemberNameException() {
        super("member_name must not be blank");
    }
}
