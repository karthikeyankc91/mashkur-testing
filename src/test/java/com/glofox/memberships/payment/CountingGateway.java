package com.glofox.memberships.payment;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test gateway that records every attempt, including the ones it fails.
 *
 * <p>Hand-written rather than a Mockito mock because the useful assertion here
 * is a <em>count</em>: an implementation that charges every caller and persists
 * only five purchases passes every state assertion while having taken 500
 * payments. Only the charge count catches that.
 */
public final class CountingGateway implements PaymentGateway {

    private final AtomicInteger charges = new AtomicInteger();
    private final Queue<Integer> amounts = new ConcurrentLinkedQueue<>();
    private final Supplier<PaymentResult> behaviour;

    private CountingGateway(Supplier<PaymentResult> behaviour) {
        this.behaviour = behaviour;
    }

    public static CountingGateway succeeding() {
        return new CountingGateway(() -> new PaymentResult("txn-" + UUID.randomUUID()));
    }

    public static CountingGateway declining() {
        return new CountingGateway(() -> {
            throw new PaymentDeclinedException("card declined");
        });
    }

    public static CountingGateway unavailable() {
        return new CountingGateway(() -> {
            throw new PaymentGatewayUnavailableException("gateway timeout");
        });
    }

    /**
     * Fails every other call, deterministically.
     *
     * <p>For property-based assertions: with an unpredictable mix of successes
     * and failures, the invariant "sold + remaining == the number that existed"
     * must still hold for every interleaving. Alternating rather than random
     * keeps the test reproducible.
     */
    public static CountingGateway failingEveryOtherCall() {
        AtomicInteger calls = new AtomicInteger();
        return new CountingGateway(() -> {
            if (calls.getAndIncrement() % 2 == 0) {
                throw new PaymentDeclinedException("card declined");
            }
            return new PaymentResult("txn-" + UUID.randomUUID());
        });
    }

    /** Throws the umbrella type, as the brief's own instructions suggest. */
    public static CountingGateway failingWithUmbrellaType() {
        return new CountingGateway(() -> {
            throw new PaymentFailedException("payment failed");
        });
    }

    @Override
    public PaymentResult charge(int amountCents, String memberName) {
        // Increment BEFORE running the behaviour. A declining gateway would
        // otherwise report zero charges and the "called exactly once even
        // though it failed" assertions would be vacuous.
        charges.incrementAndGet();
        amounts.add(amountCents);
        return behaviour.get();
    }

    /** Attempts made, successful or not. */
    public int charges() {
        return charges.get();
    }

    /**
     * Amounts passed to {@link #charge}, in arrival order. Lets callers assert
     * the amount charged equals the plan price in cents rather than merely that
     * something was charged.
     *
     * <p>Concurrent structures throughout, because the concurrency harness
     * calls this from hundreds of virtual threads; a plain {@code int} would
     * under-count and make the headline test flaky in the direction of passing.
     */
    public List<Integer> amounts() {
        return List.copyOf(amounts);
    }
}
