package com.glofox.memberships.web;

import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.service.PurchaseOutcome;
import com.glofox.memberships.service.PurchaseService;
import com.glofox.memberships.web.dto.FieldErrorResponse;
import com.glofox.memberships.web.dto.PurchaseRequest;
import com.glofox.memberships.web.dto.PurchaseResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase endpoints.
 *
 * <p><strong>This class holds no business rule and makes no status
 * decision.</strong> It validates shape, delegates, and builds a 201 with a
 * Location header; every failure leaves as an exception and is given its status
 * by {@link ApiExceptionHandler}. Replace this file with a CLI and not one rule
 * moves.
 */
@RestController
@RequestMapping("/v1/memberships/purchases")
public final class PurchaseController {

    private final PurchaseService service;

    PurchaseController(PurchaseService service) {
        this.service = service;
    }

    /**
     * Creates a purchase.
     *
     * <p>An optional {@code Idempotency-Key} header makes the call safe to
     * retry. A replay returns the <em>original</em> 201 and body plus
     * {@code Idempotency-Replayed: true} — returning 200 instead would be
     * defensible, but then a client could not tell "your retry worked" from
     * "you fetched something", and the point is that a retry is
     * indistinguishable from the first call except for that header.
     */
    @PostMapping
    ResponseEntity<PurchaseResponse> create(
            @RequestBody PurchaseRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        List<FieldErrorResponse> errors = PurchaseRequestValidator.validate(request);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }

        PurchaseOutcome outcome = service.purchase(
                request.memberName(), request.planId(), request.startDate(), idempotencyKey);
        Purchase purchase = outcome.purchase();

        return ResponseEntity
                .created(URI.create("/v1/memberships/purchases/" + purchase.id()))
                .header("Idempotency-Replayed", Boolean.toString(outcome.replayed()))
                .body(PurchaseAssembler.toResponse(purchase));
    }

    @GetMapping("/{id}")
    PurchaseResponse get(@PathVariable UUID id) {
        return PurchaseAssembler.toResponse(service.getById(id));
    }

    /**
     * Soft-cancels a purchase.
     *
     * <p>200 with the updated purchase rather than 204: the new status is useful
     * and the client should not have to re-fetch to see it.
     */
    @PostMapping("/{id}/cancel")
    PurchaseResponse cancel(@PathVariable UUID id) {
        return PurchaseAssembler.toResponse(service.cancel(id));
    }
}
