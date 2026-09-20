package dev.kitbash.api.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The wiring, not the SQL.
 *
 * <p>{@link StoreTest} builds its repositories by hand over a migrated database, which is the
 * right way to test behaviour and proves nothing about the application starting. This one boots
 * the real application with the {@code persistence} profile and asserts the two things that can
 * only be got wrong in configuration: Flyway ran the migrations from empty, and the repositories
 * became beans.
 *
 * <p>Its sibling is every other test in this module, all of which boot <i>without</i> a database —
 * which is the §10/§12 property this profile exists to preserve.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "test"})
class PersistenceProfileTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PresetRepository presets;

    @Autowired
    private VerificationRunRepository runs;

    @Test
    @DisplayName("the application starts with a database, migrates it from empty, and wires the repositories")
    void bootsAgainstAnEmptyDatabase() {
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success")
                        .query(Integer.class)
                        .single())
                .as("Flyway did not run at startup")
                .isPositive();

        assertThat(jdbc.sql(
                                """
                                select count(*) from information_schema.tables
                                where table_schema = 'public'
                                  and table_name in ('preset', 'generation', 'share_link', 'verification_run')
                                """)
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);

        assertThat(presets).isNotNull();
        assertThat(runs).isNotNull();
    }
}
