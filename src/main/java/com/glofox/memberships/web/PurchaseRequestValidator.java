package com.glofox.memberships.web;

import com.glofox.memberships.web.dto.FieldErrorResponse;
import com.glofox.memberships.web.dto.PurchaseRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Shape validation: presence and size, reported all at once.
 *
 * <p>Collecting every error before returning means a client fixing a form gets
 * one round trip instead of three.
 *
 * <p><strong>Only absence and length are checked here.</strong> Malformed values
 * ({@code "plan_id": "nope"}) never reach this method because Jackson rejects
 * them during binding. Blank-after-trim is the service's concern, since that is
 * a rule about member identity rather than about request shape — which is why
 * {@code ""} passes this validator and fails in the service.
 */
final class PurchaseRequestValidator {

    /**
     * Upper bound on the submitted name.
     *
     * <p>Not cosmetic. With no auth and no rate limit, an unbounded name is
     * retained several times over — normalised inside the member key, as a live
     * key in the claim store, and as the display string on every purchase — and
     * nothing evicts, because storage is in-memory. A few dozen multi-megabyte
     * requests would exhaust the heap.
     *
     * <p>The bound lives here rather than in {@code MemberKey} so the client
     * gets a 400 naming the field, instead of a mapped
     * {@code IllegalArgumentException}.
     */
    static final int MAX_MEMBER_NAME = 100;

    private PurchaseRequestValidator() {
    }

    static List<FieldErrorResponse> validate(PurchaseRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();

        if (request.memberName() == null) {
            errors.add(new FieldErrorResponse("member_name", "must be provided"));
        } else if (request.memberName().strip().length() > MAX_MEMBER_NAME) {
            errors.add(new FieldErrorResponse("member_name",
                    "must be at most " + MAX_MEMBER_NAME + " characters"));
        }
        if (request.planId() == null) {
            errors.add(new FieldErrorResponse("plan_id", "must be provided"));
        }
        if (request.startDate() == null) {
            errors.add(new FieldErrorResponse("start_date", "must be provided"));
        }
        return errors;
    }
}
