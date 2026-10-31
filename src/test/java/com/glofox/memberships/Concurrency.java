package com.glofox.memberships;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs N attempts at the same operation simultaneously and reports what each
 * one did.
 *
 * <p>Every task blocks on a start gate and all are released together, so the
 * contention is real rather than incidental.
 */
public final class Concurrency {

    private Concurrency() {
    }

    /** What one attempt did: a value, or the throwable that stopped it. */
    public record Outcome<T>(T value, Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * Races {@code attempts} concurrent invocations of {@code task}.
     *
     * <p>Four decisions here, and the first is the one that matters:
     *
     * <ol>
     *   <li><strong>Virtual threads are a correctness requirement of this
     *       pattern, not a performance choice.</strong> With a bounded platform
     *       pool, 500 tasks each blocking on the start gate would occupy every
     *       worker, so the remaining tasks would never start and the gate would
     *       never be reached by all of them — the test would run a trickle of
     *       tasks almost serially and quietly stop testing contention at all.
     *       Virtual threads unmount while blocked, so the barrier is safe at any
     *       N. A {@code newFixedThreadPool(8)} here is the classic way this test
     *       keeps passing while verifying nothing.
     *   <li><strong>Explicit {@code shutdown()} plus a bounded
     *       {@code awaitTermination} assertion</strong>, rather than
     *       try-with-resources. {@code ExecutorService.close()} waits
     *       indefinitely, so a deadlock in the code under test would become a
     *       hung build with no message; this way it is a failed assertion that
     *       names the problem.
     *   <li><strong>{@code Throwable}, not {@code Exception}.</strong> An
     *       {@code AssertionError} or {@code StackOverflowError} inside a task
     *       must be captured rather than swallowed into a lost result, which
     *       would make the counts silently wrong.
     *   <li><strong>{@code ConcurrentLinkedQueue}, then {@code List.copyOf}.</strong>
     *       Collecting into an {@code ArrayList} from 500 threads drops entries
     *       without ever throwing, and a count that is quietly too low looks
     *       exactly like a pass.
     * </ol>
     */
    public static <T> List<Outcome<T>> raceAll(int attempts, Callable<T> task) {
        CountDownLatch startGate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Outcome<T>> results = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    startGate.await();
                    try {
                        results.add(new Outcome<>(task.call(), null));
                    } catch (Throwable failure) {
                        results.add(new Outcome<>(null, failure));
                    }
                    return null;
                });
            }
            startGate.countDown();
        } finally {
            pool.shutdown();
            boolean finished;
            try {
                finished = pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting attempts", e);
            }
            assertThat(finished)
                    .as("all %d attempts finished within 30s", attempts)
                    .isTrue();
        }

        List<Outcome<T>> outcomes = List.copyOf(results);
        assertThat(outcomes)
                .as("every attempt recorded an outcome")
                .hasSize(attempts);
        return outcomes;
    }

    public static <T> List<T> successes(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
    }

    public static <T> List<Throwable> failures(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::failure).toList();
    }
}
