package com.glofox.memberships.service;

import com.glofox.memberships.domain.MemberKey;

/**
 * The member already holds an active unlimited membership.
 *
 * <p>Carries the normalised {@link MemberKey} rather than the submitted name.
 * The key is already folded, so it cannot carry control characters from the
 * request into a log line. The web layer must still not echo it back to the
 * caller — doing so would confirm whether a given name holds a membership.
 */
public final class UnlimitedMembershipExistsException extends RuntimeException {

    private final MemberKey memberKey;

    public UnlimitedMembershipExistsException(MemberKey memberKey) {
        super("Member already holds an active unlimited membership");
        this.memberKey = memberKey;
    }

    public MemberKey memberKey() {
        return memberKey;
    }
}
