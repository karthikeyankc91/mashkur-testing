package com.glofox.memberships.service;

import com.glofox.memberships.domain.MemberKey;
import com.glofox.memberships.domain.Plan;
import com.glofox.memberships.domain.PlanType;
import com.glofox.memberships.domain.Purchase;
import com.glofox.memberships.domain.PurchaseStatus;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.store.IdempotencyStore;
import com.glofox.memberships.store.InventoryStore;
import com.glofox.memberships.store.PlanCatalogue;
import com.glofox.memberships.store.PurchaseStore;
import com.glofox.memberships.store.UnlimitedClaimStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every membership business rule lives here.
 *
 * <p>Carries no {@code @Service} annotation. The rule for the whole codebase is
 * that {@code config/} and {@code web/} may import Spring and
 * {@code domain/ store/ storage/ service/} may not, which makes the layering
 * boundary a single greppable command instead of four with exceptions. The cost
 * is one small configuration class; the benefit is that this service is
 * constructible by hand, which is what every test here does.
 *
 * <h2>Why the ordering is reserve, then charge, then commit</h2>
 *
 * In-memory storage offers no transaction to roll back, so <em>ordering is</em>
 * the consistency story. Claims are taken before the charge because a claim is
 * cheap and reversible while a charge is neither. Charging first and then
 * discovering the pack is sold out would mean taking money for something that
 * cannot be delivered — that is a defect, not a trade-off.
 *
 * <p>Every claim pushes its own undo onto a stack. If anything later throws,
 * the stack unwinds in reverse and the original failure is rethrown.
 */
public final class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final PlanCatalogue catalogue;
    private final InventoryStore inventory;
    private final UnlimitedClaimStore claims;
    private final PurchaseStore purchases;
    private final IdempotencyStore idempotency;
    private final PaymentGateway gateway;
    private final StartDatePolicy startDates;
    private final PurchaseFactory factory;

    public PurchaseService(PlanCatalogue catalogue,
                           InventoryStore inventory,
                           UnlimitedClaimStore claims,
                           PurchaseStore purchases,
                           IdempotencyStore idempotency,
                           PaymentGateway gateway,
                           StartDatePolicy startDates,
                           PurchaseFactory factory) {
        this.catalogue = catalogue;
        this.inventory = inventory;
        this.claims = claims;
        this.purchases = purchases;
        this.idempotency = idempotency;
        this.gateway = gateway;
        this.startDates = startDates;
        this.factory = factory;
    }

    /**
     * Purchases a membership.
     *
     * <p>Three parameters rather than a command object: the web DTO cannot cross
     * this boundary, and a record duplicating its three fields would be a type
     * with no behaviour.
     */
    public Purchase purchase(String memberName, UUID planId, LocalDate startDate) {
        return purchase(memberName, planId, startDate, null).purchase();
    }

    /**
     * Purchases a membership, optionally deduplicated by an idempotency key.
     *
     * <p>A null or blank key means exactly the behaviour above — no key store is
     * touched at all.
     *
     * <p>The key claim becomes <strong>compensation entry #0</strong> on the
     * same stack the rest of the flow uses, so a failed charge releases the key
     * through the existing unwind. Without that, an honest client retrying after
     * a declined card would be told its request was already in progress
     * forever.
     */
    public PurchaseOutcome purchase(String memberName, UUID planId, LocalDate startDate,
                                    String idempotencyKey) {

        // --- semantics the service owns, cheapest first ----------------------
        requireNonBlank(memberName);
        startDates.requireNotPast(startDate);

        // --- resolve the plan, then check its state --------------------------
        Plan plan = catalogue.find(planId)
                .orElseThrow(() -> new PlanNotFoundException(planId));
        if (!plan.active()) {
            // The decision is taken here rather than inside a throwing domain
            // method, so every rule in this flow is visible in one place.
            throw new PlanInactiveException(plan);
        }

        // --- identity and the compensation stack -----------------------------
        MemberKey key = new MemberKey(memberName);
        // Generated before the first claim because the claim is recorded against
        // this id: it is what makes a release provably ours.
        UUID purchaseId = UUID.randomUUID();

        // ArrayDeque with push/pop makes "unwind in reverse" structural. A List
        // plus Collections.reverse expresses the same thing as an extra step
        // someone can forget; here the data structure IS the LIFO requirement.
        Deque<Runnable> compensations = new ArrayDeque<>();

        try {
            // --- entry #0: claim the idempotency key, if one was supplied ----
            // Same claim-or-fail shape as the two below. Pushed first so it is
            // released last, after the inventory and claim it guards.
            boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();
            if (keyed) {
                String fingerprint = fingerprintOf(memberName, planId, startDate);
                switch (idempotency.claim(idempotencyKey, fingerprint)) {
                    case IdempotencyStore.Claim.Replay(UUID existingId) -> {
                        // Nothing is charged on this path. Return the original.
                        return PurchaseOutcome.replayed(getById(existingId));
                    }
                    case IdempotencyStore.Claim.InProgress() ->
                            throw new IdempotentRequestInProgressException();
                    case IdempotencyStore.Claim.Conflict() ->
                            throw new IdempotencyKeyReuseException();
                    case IdempotencyStore.Claim.Acquired() ->
                            compensations.push(() -> idempotency.release(idempotencyKey));
                }
            }

            // --- claim the member's single unlimited slot --------------------
            if (plan.type() == PlanType.UNLIMITED) {
                if (!claims.tryClaim(key, purchaseId)) {
                    throw new UnlimitedMembershipExistsException(key);
                }
                compensations.push(() -> releaseClaim(key, purchaseId));
            }

            // --- reserve finite inventory ------------------------------------
            // The test is "does this plan have a counter?", not "is it a pack?".
            // Pack-ness and finite inventory are independent concerns, and only
            // the inventory store knows which plans have counters at all.
            if (inventory.remaining(planId).isPresent()) {
                if (!inventory.tryReserve(planId)) {
                    throw new PlanSoldOutException(plan);
                }
                compensations.push(() -> inventory.release(planId));
            }

            // --- charge. No lock is held across this call --------------------
            // Everything contended was settled by the two atomic claims above,
            // so the slowest operation in the flow blocks nobody.
            PaymentGateway.PaymentResult result =
                    gateway.charge(plan.priceCents(), memberName);

            // --- commit ------------------------------------------------------
            // memberName is stored as submitted, for display; the normalised key
            // is what matching uses.
            Purchase purchase = factory.create(purchaseId, memberName, key, plan,
                                               startDate, result.transactionId());
            purchases.save(purchase);

            // Recorded only after the purchase is saved, so a retry can never
            // replay an id that is not yet retrievable.
            if (keyed) {
                idempotency.complete(idempotencyKey, purchaseId);
            }
            return PurchaseOutcome.created(purchase);

        } catch (RuntimeException original) {
            // Catching RuntimeException rather than PaymentFailedException is
            // deliberate: this one path must also clean up after a sold-out
            // throw, a claim conflict, or anything a store does unexpectedly.
            unwind(compensations, original);
            throw original;
        }
    }

    public Purchase getById(UUID id) {
        // The service throws, not the controller, so every mapped error
        // originates in one layer.
        return purchases.findById(id)
                .orElseThrow(() -> new PurchaseNotFoundException(id));
    }

    /**
     * Soft-cancels a purchase and gives back whatever it claimed.
     *
     * <h2>Why the compare-and-set comes first</h2>
     *
     * Restoring first and transitioning the status afterwards looks equivalent
     * and is not. Two concurrent cancels of the same purchase would both read
     * {@code ACTIVE}, both restore the promo slot, and only then would one lose
     * the transition — leaving the counter one <em>above</em> where it belongs.
     * That oversells the pack <strong>through the cancel endpoint</strong>: six
     * people holding a five-slot promo.
     *
     * <p>The compare-and-set is what <em>elects</em> the single caller entitled
     * to compensate. It is the purchase path's insight pointing the other way:
     * there, an atomic claim precedes the work it authorises; here, an atomic
     * transition precedes the compensation it authorises.
     *
     * <p>No refund is issued — out of scope for this exercise.
     */
    public Purchase cancel(UUID purchaseId) {
        Purchase purchase = purchases.findById(purchaseId)
                .orElseThrow(() -> new PurchaseNotFoundException(purchaseId));

        if (!purchases.compareAndSetStatus(purchaseId,
                                           PurchaseStatus.ACTIVE,
                                           PurchaseStatus.CANCELLED)) {
            throw new PurchaseAlreadyCancelledException(purchaseId);
        }

        // Only the winner of the transition reaches here, so each compensation
        // below runs exactly once however many callers raced.
        if (purchase.plan().type() == PlanType.UNLIMITED) {
            releaseClaim(purchase.memberKey(), purchaseId);
        }
        // Unconditional: release is already a no-op for plans with no counter.
        // Guarding it on plan type would reintroduce the confusion between
        // pack-ness and finite inventory that purchase() deliberately avoids.
        inventory.release(purchase.plan().planId());

        return purchases.findById(purchaseId)
                .orElseThrow(() -> new PurchaseNotFoundException(purchaseId));
    }

    /**
     * Runs every compensation, newest first, without ever losing the real cause.
     *
     * <p>A failure handler that can itself fail is not a failure handler. If a
     * release throws, it is attached to the original exception with
     * {@code addSuppressed} and logged, and the unwind continues — so one broken
     * compensation cannot strand the others or replace the exception the caller
     * needs to see.
     */
    private static void unwind(Deque<Runnable> compensations, RuntimeException original) {
        while (!compensations.isEmpty()) {
            try {
                compensations.pop().run();
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
                log.error("compensation failed during unwind", cleanupFailure);
            }
        }
    }

    private void releaseClaim(MemberKey key, UUID purchaseId) {
        if (!claims.release(key, purchaseId)) {
            // Reaching here means we tried to release a claim we no longer
            // owned, which should be impossible. Log it rather than throw: the
            // caller is already handling a failure and this is diagnostic.
            log.error("invariant breach: released an unlimited claim we did not own, "
                      + "member={} purchase={}", forLog(key.value()), purchaseId);
        }
    }

    /**
     * Sanitises a caller-controlled value before it reaches a log.
     *
     * <p>An unsanitised member name of
     * {@code "Alice\n2026-10-31 ERROR [c.g.m.PurchaseService] inventory reconciled"}
     * forges a log line, and ANSI escapes in the same field can manipulate a
     * terminal tailing the log. The cheapest sanitisation is not emitting the
     * value at all, which is why most log statements here prefer the
     * server-generated purchase id.
     */
    private static String forLog(String raw) {
        return raw == null ? "null" : raw.replaceAll("[\\r\\n\\p{Cntrl}]", "_");
    }

    /**
     * A fixed-size digest of the request, so one key reused with a different
     * body is detectable.
     *
     * <p>A hash rather than the raw payload: storing the submitted name here
     * would retain an unbounded caller-controlled string a fourth time, which
     * is the heap concern the length bound addresses. A digest is 64 characters
     * whatever the input.
     */
    private static String fingerprintOf(String memberName, UUID planId, LocalDate startDate) {
        String payload = memberName + "|" + planId + "|" + startDate;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM implementation.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireNonBlank(String memberName) {
        if (memberName == null || memberName.strip().isEmpty()) {
            throw new BlankMemberNameException();
        }
    }
}
