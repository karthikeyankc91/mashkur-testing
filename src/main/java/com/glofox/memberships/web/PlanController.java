package com.glofox.memberships.web;

import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import com.glofox.memberships.web.dto.PlanResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the plans on offer, including inactive ones.
 *
 * <p><strong>There is no PlanService and the controller holds two ports
 * directly.</strong> Listing enforces no business rule, and a service with no
 * content would be a layer added for symmetry rather than for a reason. If a
 * rule ever appears here, introducing one is a two-minute change.
 */
@RestController
@RequestMapping("/v1/plans")
public final class PlanController {

    private final PlanCatalogue catalogue;
    private final InventoryStore inventory;

    PlanController(PlanCatalogue catalogue, InventoryStore inventory) {
        this.catalogue = catalogue;
        this.inventory = inventory;
    }

    /** Returns a bare JSON array: no envelope, as the contract specifies. */
    @GetMapping
    public List<PlanResponse> list() {
        // Composing two sources here is the entire price paid for keeping the
        // catalogue immutable and lock-free: plans and inventory live apart
        // because one is fixed and the other mutates.
        return catalogue.listInSeedOrder().stream()
                .map(plan -> PlanAssembler.toResponse(plan, inventory.remaining(plan.id())))
                .toList();
    }
}
