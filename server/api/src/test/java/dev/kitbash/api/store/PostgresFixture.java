package dev.kitbash.api.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres 16, shared by every test in this module.
 *
 * <p>The version matters: {@code jsonb}, {@code on conflict do nothing} and partial unique indexes
 * are all Postgres features this schema leans on, and testing them against anything else — an
 * in-memory database in compatibility mode, say — would be testing a different database than the
 * one the migrations will meet.
 *
 * <p>Public because it is shared beyond this package: {@code kitbash-37}'s dedupe is a property of
 * the same schema and has to be tested against the same database.
 *
 * <p>One container for the whole JVM rather than one per class. Starting Postgres takes a couple of
 * seconds; paying that per test class is what makes people stop writing the tests.
 */
public final class PostgresFixture {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    private PostgresFixture() {}

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(POSTGRES.getJdbcUrl());
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(4);
            dataSource = new HikariDataSource(config);
            migrate();
        }
        return dataSource;
    }

    public static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Flyway, configured exactly as {@code application.yaml} configures it. */
    public static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();
    }

    public static void migrate() {
        flyway().migrate();
    }

    /**
     * Between tests, not between suites. Truncating is enough because nothing here tests the
     * migration's own idempotency except the one test that does, and that one re-runs Flyway
     * rather than the schema.
     */
    public static void clean() {
        jdbc().sql("truncate preset, generation, share_link, verification_run cascade")
                .update();
    }
}
