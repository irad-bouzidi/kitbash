package dev.kitbash.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The five filters, each one documented in {@code docs/recipe-format.md} and pinned here.
 *
 * <p>They are total by design: any of the five naming forms converts to any other, because a
 * recipe should never have to know whether the caller typed {@code customer-management} or {@code
 * CustomerManagement}.
 */
class FiltersTest {

    @ParameterizedTest(name = "{0} | {1} -> {2}")
    @CsvSource({
        "com.acme.customer, packagePath, com/acme/customer",
        "customer-management, camel, customerManagement",
        "customer_management, camel, customerManagement",
        "CustomerManagement, camel, customerManagement",
        "customer-management, pascal, CustomerManagement",
        "customerManagement, kebab, customer-management",
        "CustomerManagement, snake, customer_management",
        "customer management, kebab, customer-management",
        "HTTPServer, kebab, http-server",
        "order2Item, kebab, order2-item",
    })
    @DisplayName("every filter converts every naming form")
    void converts(String input, String filter, String expected) {
        TemplateEngine engine = TemplateEngine.over(TemplateRegistry.of(Map.of("t", "{{ value | " + filter + " }}")));

        assertThat(engine.render(
                        "t",
                        "base",
                        TemplateVariables.builder().put("value", input).build()))
                .isEqualTo(expected);
    }
}
