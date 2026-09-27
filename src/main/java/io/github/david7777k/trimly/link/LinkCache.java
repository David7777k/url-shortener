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
 * Caches the one thing the redirect needs: code to target URL.
 *
 * <p>Every method swallows Redis failures. The cache exists to spare the
 * database, not to be a dependency of its own - if Redis is unreachable the
 * service should get slower, not stop. Short client timeouts back that up: a
 * cache that takes longer to answer than the query it replaces is worse than no
 * cache.
 */
@Component
public class LinkCache {

    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);

    private static final String KEY_PREFIX = "link:";

    /**
     * Marks a code that is known not to exist.
     *
     * <p>Without remembering misses, a flood of requests for codes that were
     * never issued walks straight past the cache into the database every time -
     * the cache is useless precisely when it is needed most. Not a valid target,
     * since a stored URL always begins with http.
     */
    private static final String MISSING = "\u0000missing";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration missingTtl;

    public LinkCache(StringRedisTemplate redis,
                     @Value("${trimly.cache.ttl:PT1H}") Duration ttl,
                     @Value("${trimly.cache.missing-ttl:PT1M}") Duration missingTtl) {
        this.redis = redis;
        this.ttl = ttl;
        this.missingTtl = missingTtl;
    }

    /**
     * @return empty when nothing is cached, otherwise a hit that either carries
     *         the target or records that the code does not exist
     */
    public Optional<Hit> lookup(String code) {
        try {
            String cached = redis.opsForValue().get(key(code));
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(MISSING.equals(cached) ? Hit.missing() : Hit.of(cached));
        } catch (DataAccessException e) {
            log.warn("Cache lookup failed for {}, falling through to the database", code);
            return Optional.empty();
        }
    }

    public void put(String code, String targetUrl) {
        write(code, targetUrl, ttl);
    }

    public void putMissing(String code) {
        write(code, MISSING, missingTtl);
    }

    public void evict(String code) {
        try {
            redis.delete(key(code));
        } catch (DataAccessException e) {
            // The entry will expire on its own. Worth a warning, not a failure:
            // refusing the delete because the cache is down would be worse.
            log.warn("Cache eviction failed for {}", code);
        }
    }

    private void write(String code, String value, Duration expiry) {
        try {
            redis.opsForValue().set(key(code), value, expiry);
        } catch (DataAccessException e) {
            log.warn("Cache write failed for {}", code);
        }
    }

    private static String key(String code) {
        return KEY_PREFIX + code;
    }

    /** A cached answer: either a target, or the knowledge that there is none. */
    public record Hit(String targetUrl) {

        static Hit of(String targetUrl) {
            return new Hit(targetUrl);
        }

        static Hit missing() {
            return new Hit(null);
        }

        public boolean isMissing() {
            return targetUrl == null;
        }
    }
}
