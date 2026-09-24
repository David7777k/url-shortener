package io.github.david7777k.trimly;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by the whole test run.
 *
 * <p>Tables are truncated between tests rather than rolled back, so writes stay
 * visible to other connections - the concurrency tests later need that.
 */
@SpringBootTest
@Import(AbstractIntegrationTest.DatabaseConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @AfterEach
    void truncateAllTables() {
        jdbcTemplate.execute("truncate table link restart identity cascade");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {

        @Bean
        DynamicPropertyRegistrar postgresProperties() {
            return registry -> {
                registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
                registry.add("spring.datasource.username", POSTGRES::getUsername);
                registry.add("spring.datasource.password", POSTGRES::getPassword);
            };
        }
    }
}
