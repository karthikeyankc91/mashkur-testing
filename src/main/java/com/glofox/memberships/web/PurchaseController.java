package com.glofox.memberships.web;

import com.glofox.memberships.domain.Purchase;
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

    @PostMapping
    ResponseEntity<PurchaseResponse> create(@RequestBody PurchaseRequest request) {
        List<FieldErrorResponse> errors = PurchaseRequestValidator.validate(request);
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }

        Purchase purchase = service.purchase(
                request.memberName(), request.planId(), request.startDate());

        return ResponseEntity
                .created(URI.create("/v1/memberships/purchases/" + purchase.id()))
                .body(PurchaseAssembler.toResponse(purchase));
    }

    @GetMapping("/{id}")
    PurchaseResponse get(@PathVariable UUID id) {
        return PurchaseAssembler.toResponse(service.getById(id));
    }
}
