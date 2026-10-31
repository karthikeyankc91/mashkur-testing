package com.glofox.memberships.storage.memory;

import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.store.PlanCatalogue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue over an immutable snapshot of the seeded plans.
 *
 * <p><strong>There is no synchronization here and that is the design, not an
 * omission.</strong> Both structures are built once in the constructor and
 * never mutated, so the catalogue is thread-safe by immutability. Holding two
 * views of the same data — ordered list and lookup map — is only safe because
 * neither can change; with a mutable catalogue the two could disagree.
 */
public final class InMemoryPlanCatalogue implements PlanCatalogue {

    private final List<Plan> ordered;
    private final Map<UUID, Plan> byId;

    public InMemoryPlanCatalogue(List<Plan> plans) {
        this.ordered = List.copyOf(plans);
        // toUnmodifiableMap throws on a duplicate key, so duplicate seed ids
        // fail the boot. Free validation; nothing extra to write.
        this.byId = this.ordered.stream()
                .collect(Collectors.toUnmodifiableMap(Plan::id, Function.identity()));
    }

    @Override
    public Optional<Plan> find(UUID planId) {
        return Optional.ofNullable(byId.get(planId));
    }

    @Override
    public List<Plan> listInSeedOrder() {
        return ordered;
    }
}
