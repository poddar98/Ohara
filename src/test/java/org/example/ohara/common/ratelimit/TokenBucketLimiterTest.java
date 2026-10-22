package org.example.ohara.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketLimiterTest {

    private final AtomicLong clock = new AtomicLong();
    private final TokenBucketLimiter limiter = new TokenBucketLimiter(clock::get);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void allowsTheCapacityThenRejectsWithARetryHint() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, MINUTE).allowed()).isTrue();
        }
        TokenBucketLimiter.Decision rejected = limiter.tryAcquire("k", 5, MINUTE);

        assertThat(rejected.allowed()).isFalse();
        // One token refills every 12 s.
        assertThat(rejected.retryAfterSeconds()).isBetween(12L, 13L);
    }

    @Test
    void refillsContinuouslyAndNeverAboveCapacity() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", 5, MINUTE);
        }
        clock.addAndGet(Duration.ofSeconds(12).toNanos());
        assertThat(limiter.tryAcquire("k", 5, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 5, MINUTE).allowed()).isFalse();

        clock.addAndGet(Duration.ofHours(1).toNanos());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, MINUTE).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 5, MINUTE).allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", 3, MINUTE);
        }
        assertThat(limiter.tryAcquire("a", 3, MINUTE).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 3, MINUTE).allowed()).isTrue();
    }
}
