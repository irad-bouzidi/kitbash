package dev.kitbash.api.catalog;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names the published document, because the web client's types are generated from it and a spec
 * titled "OpenAPI definition v0" tells a reader nothing about what it describes.
 *
 * <p>Deliberately free of the catalog digest: the spec describes the API's <i>shape</i>, which does
 * not change when a recipe is added. Putting the digest in here would churn the checked-in copy —
 * and the TypeScript generated from it — on every catalog edit, for no change in the types.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI kitbashOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kitbash API")
                        .version("v1")
                        .description("Generate a project that already builds. The catalog is served by /metadata "
                                + "and everything else is driven from it — a client hardcodes no option "
                                + "name, and adding a recipe is a backend-only change."));
    }
}
