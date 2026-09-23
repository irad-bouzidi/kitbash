package dev.kitbash.api.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The §10 repositories, which exist only when there is a database.
 *
 * <p>Declared here rather than component-scanned as {@code @Repository}, because the profile is
 * the point: without it the beans would be instantiated on every boot and fail looking for a
 * {@code JdbcClient} that the excluded autoconfiguration never created. A generator that cannot
 * start without Postgres would make {@code /generate} hostage to a feature it does not use.
 */
@Configuration
@Profile("persistence")
public class PersistenceConfiguration {

    @Bean
    public PresetRepository presetRepository(JdbcClient jdbc) {
        return new PresetRepository(jdbc);
    }

    @Bean
    public GenerationRepository generationRepository(JdbcClient jdbc) {
        return new GenerationRepository(jdbc);
    }

    @Bean
    public ShareLinkRepository shareLinkRepository(JdbcClient jdbc) {
        return new ShareLinkRepository(jdbc);
    }

    @Bean
    public VerificationRunRepository verificationRunRepository(JdbcClient jdbc) {
        return new VerificationRunRepository(jdbc);
    }

    /** kitbash-40's history cells, which exist only where there is history. */
    @Bean
    public dev.kitbash.api.history.HistoryCells historyCells(dev.kitbash.api.generate.PopularSelections popular) {
        return new dev.kitbash.api.history.HistoryCells(popular);
    }
}
