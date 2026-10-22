package org.example.ohara.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets keyed by an arbitrary string (e.g. "login:203.0.113.7"). Each bucket holds up to
 * {@code capacity} tokens and refills continuously at {@code capacity} per {@code period}.
 * Per-instance only: with several API nodes this moves to a shared store (Redis), see ADR-014.
 */
public class TokenBucketLimiter {

    /** Outcome of one attempt; {@code retryAfterSeconds} is meaningful only when not allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final AtomicLong operations = new AtomicLong();

    public TokenBucketLimiter() {
        this(System::nanoTime);
    }

    TokenBucketLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public Decision tryAcquire(String key, int capacity, Duration period) {
        if (operations.incrementAndGet() % 1_000 == 0 && buckets.size() > CLEANUP_THRESHOLD) {
            evictIdle(period);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong()));
        return bucket.tryAcquire(capacity, period.toNanos(), nanoClock.getAsLong());
    }

    /** Full buckets carry no state worth keeping. */
    private void evictIdle(Duration period) {
        long now = nanoClock.getAsLong();
        buckets.entrySet().removeIf(e -> e.getValue().isFullAt(now, period.toNanos()));
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        synchronized Decision tryAcquire(int capacity, long periodNanos, long now) {
            refill(capacity, periodNanos, now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            double nanosPerToken = (double) periodNanos / capacity;
            long waitNanos = (long) Math.ceil((1 - tokens) * nanosPerToken);
            return new Decision(false, Math.max(1, Duration.ofNanos(waitNanos).toSeconds() + 1));
        }

        synchronized boolean isFullAt(long now, long periodNanos) {
            return now - lastRefill >= periodNanos;
        }

        private void refill(int capacity, long periodNanos, long now) {
            long elapsed = now - lastRefill;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (double) elapsed * capacity / periodNanos);
                lastRefill = now;
            }
        }
    }
}
