package io.github.david7777k.trimly.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Token bucket, written out rather than taken from a library.
 *
 * <p>Why a bucket and not a fixed window: a fixed counter reset every minute
 * lets a client spend its whole allowance at 11:59:59 and again at 12:00:00 -
 * twice the intended rate across the boundary. A bucket refills continuously,
 * so the average rate holds while still permitting a burst up to its capacity.
 *
 * <p>The decision is made by a Lua script inside Redis. Reading the count,
 * deciding, and writing it back as separate commands is a read-then-write race:
 * two requests can both read the same count and both be let through. A script
 * runs without another client interleaving.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;
    private final Clock clock;

    private final int capacity;
    private final double refillPerSecond;
    private final int keyTtlSeconds;
    private final boolean failOpen;

    public RateLimiter(StringRedisTemplate redis,
                       Clock clock,
                       @Value("${trimly.rate-limit.capacity:20}") int capacity,
                       @Value("${trimly.rate-limit.refill-per-second:1}") double refillPerSecond,
                       @Value("${trimly.rate-limit.key-ttl-seconds:3600}") int keyTtlSeconds,
                       @Value("${trimly.rate-limit.fail-open:true}") boolean failOpen) {
        this.redis = redis;
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.keyTtlSeconds = keyTtlSeconds;
        this.failOpen = failOpen;

        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    /**
     * Spends one token for {@code clientId}.
     *
     * <p>When Redis is unreachable the outcome is governed by {@code fail-open},
     * which defaults to letting the request through. The limiter protects
     * against abuse of link creation; refusing every legitimate caller because
     * the limiter is down does more damage than the abuse it would prevent.
     * A limiter guarding something expensive - a payment, an outbound SMS -
     * should be configured the other way.
     */
    public Decision check(String clientId) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redis.execute(
                    script,
                    List.of(KEY_PREFIX + clientId),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(clock.millis()),
                    "1",
                    String.valueOf(keyTtlSeconds));

            if (result == null || result.size() < 3) {
                return degraded();
            }

            return new Decision(
                    result.get(0) == 1L,
                    capacity,
                    result.get(1).intValue(),
                    result.get(2));

        } catch (DataAccessException e) {
            log.warn("Rate limiter backend unavailable, failing {}", failOpen ? "open" : "closed");
            return degraded();
        }
    }

    private Decision degraded() {
        return new Decision(failOpen, capacity, failOpen ? capacity : 0, failOpen ? 0 : 1000);
    }

    /**
     * @param allowed      whether the request may proceed
     * @param limit        bucket capacity, reported as X-RateLimit-Limit
     * @param remaining    tokens left after this request
     * @param retryAfterMs how long until one token is available again
     */
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterMs) {

        /** Seconds, rounded up: Retry-After has no sub-second form. */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfterMs + 999) / 1000);
        }
    }
}
