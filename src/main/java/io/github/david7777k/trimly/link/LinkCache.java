package io.github.david7777k.trimly.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches what the redirect needs: the target URL and the link id.
 *
 * <p>The id is cached alongside the URL because recording a click needs it.
 * Without it every redirect would still hit the database to find out which link
 * it belonged to, which is the query the cache exists to avoid.
 *
 * <p>Stored as {@code id|url} rather than JSON. The value is two fields written
 * and read in one place, and a serialiser would be more machinery than the
 * problem has.
 *
 * <p>Every method swallows Redis failures. The cache exists to spare the
 * database, not to become a dependency - if Redis is unreachable the service
 * should get slower, not stop. Short client timeouts back that up: a cache that
 * answers slower than the query it replaces is worse than no cache.
 */
@Component
public class LinkCache {

    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);

    private static final String KEY_PREFIX = "link:";
    private static final char SEPARATOR = '|';

    /**
     * Marks a code known not to exist.
     *
     * <p>Without remembering misses, requests for codes that were never issued
     * reach the database every time - the cache stops helping exactly when it
     * faces the most junk traffic. Not a valid value, since a real one starts
     * with a numeric id.
     */
    private static final String MISSING = "\u0000missing";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration missingTtl;

    /**
     * Lets the cache be switched off without removing it, so the same build can
     * be measured with and without. A benchmark comparing two different builds
     * measures the difference between the builds as much as the cache.
     */
    private final boolean enabled;

    public LinkCache(StringRedisTemplate redis,
                     @Value("${trimly.cache.ttl:PT1H}") Duration ttl,
                     @Value("${trimly.cache.missing-ttl:PT1M}") Duration missingTtl,
                     @Value("${trimly.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.ttl = ttl;
        this.missingTtl = missingTtl;
        this.enabled = enabled;
    }

    /**
     * @return empty when nothing is cached, otherwise a hit that either carries
     *         the link or records that the code does not exist
     */
    public Optional<Hit> lookup(String code) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String cached = redis.opsForValue().get(key(code));
            if (cached == null) {
                return Optional.empty();
            }
            if (MISSING.equals(cached)) {
                return Optional.of(Hit.missing());
            }
            return Optional.of(parse(cached));
        } catch (DataAccessException e) {
            log.warn("Cache lookup failed for {}, falling through to the database", code);
            return Optional.empty();
        }
    }

    public void put(String code, long linkId, String targetUrl) {
        write(code, linkId + String.valueOf(SEPARATOR) + targetUrl, ttl);
    }

    public void putMissing(String code) {
        write(code, MISSING, missingTtl);
    }

    public void evict(String code) {
        try {
            redis.delete(key(code));
        } catch (DataAccessException e) {
            // The entry expires on its own. Worth a warning, not a failure:
            // refusing the delete because the cache is down would be worse.
            log.warn("Cache eviction failed for {}", code);
        }
    }

    private void write(String code, String value, Duration expiry) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key(code), value, expiry);
        } catch (DataAccessException e) {
            log.warn("Cache write failed for {}", code);
        }
    }

    private static Hit parse(String cached) {
        int separator = cached.indexOf(SEPARATOR);
        if (separator <= 0) {
            // Written by an older version, or corrupted. Treating it as a miss
            // costs one database query; trusting it could redirect somewhere
            // unintended.
            return Hit.missing();
        }
        try {
            long linkId = Long.parseLong(cached.substring(0, separator));
            return new Hit(linkId, cached.substring(separator + 1));
        } catch (NumberFormatException e) {
            return Hit.missing();
        }
    }

    private static String key(String code) {
        return KEY_PREFIX + code;
    }

    /** A cached answer: either a link, or the knowledge that there is none. */
    public record Hit(Long linkId, String targetUrl) {

        static Hit missing() {
            return new Hit(null, null);
        }

        public boolean isMissing() {
            return targetUrl == null;
        }
    }
}
