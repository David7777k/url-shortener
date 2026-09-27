package io.github.david7777k.trimly;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL and one Redis container shared by the whole test run.
 *
 * <p>Tables are truncated between tests rather than rolled back, so writes stay
 * visible to other connections.
 */
@SpringBootTest
@Import(AbstractIntegrationTest.ContainerConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * The 2.x line has no Redis module, and none is needed - a plain container
     * with the port exposed is the whole requirement.
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @AfterEach
    void resetState() {
        jdbcTemplate.execute("truncate table link restart identity cascade");

        // Cached entries would otherwise leak into the next test and answer
        // from a row that no longer exists.
        try {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        } catch (RuntimeException ignored) {
            // The cache-unavailable test has no reachable Redis by design.
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ContainerConfiguration {

        @Bean
        DynamicPropertyRegistrar containerProperties() {
            return registry -> {
                registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
                registry.add("spring.datasource.username", POSTGRES::getUsername);
                registry.add("spring.datasource.password", POSTGRES::getPassword);
                registry.add("spring.data.redis.host", REDIS::getHost);
                registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            };
        }
    }
}
