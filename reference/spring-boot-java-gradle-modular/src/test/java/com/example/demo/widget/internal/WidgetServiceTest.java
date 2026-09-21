package com.example.demo.widget.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The rules the module enforces, tested without a database or a Spring context.
 *
 * <p>The test lives inside the module's own package because the things worth testing here are
 * package-private. A module whose unit tests have to be written from outside it is a module whose
 * internals are not internal.
 */
@ExtendWith(MockitoExtension.class)
class WidgetServiceTest {

    @Mock
    private WidgetRepository widgets;

    private WidgetService service;

    @BeforeEach
    void setUp() {
        service = new WidgetService(widgets);
    }

    @Test
    @DisplayName("creating a widget whose name is taken is rejected before it reaches the database")
    void rejectsDuplicateName() {
        when(widgets.existsByName("flux capacitor")).thenReturn(true);

        assertThatThrownBy(() -> service.create("flux capacitor", 1))
                .isInstanceOf(DuplicateWidgetNameException.class)
                .hasMessageContaining("flux capacitor");

        verify(widgets, never()).save(any());
    }

    @Test
    @DisplayName("fetching an unknown id reports the id that was not found")
    void reportsMissingId() {
        when(widgets.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(404L))
                .isInstanceOf(WidgetNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("restocking adds to the existing quantity")
    void restockAdds() {
        Widget widget = Widget.of("flux capacitor", 2);
        when(widgets.findById(1L)).thenReturn(Optional.of(widget));

        assertThat(service.restock(1L, 5).quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("a negative restock is a programming error, not a quiet decrement")
    void rejectsNegativeRestock() {
        Widget widget = Widget.of("flux capacitor", 2);
        when(widgets.findById(1L)).thenReturn(Optional.of(widget));

        assertThatThrownBy(() -> service.restock(1L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(widget.quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("the published summary carries only what another module is allowed to see")
    void publishesSummariesRatherThanEntities() {
        Widget widget = Widget.of("flux capacitor", 2);
        when(widgets.findAll()).thenReturn(java.util.List.of(widget));

        assertThat(service.summaries()).singleElement().satisfies(summary -> {
            assertThat(summary.name()).isEqualTo("flux capacitor");
            assertThat(summary.quantity()).isEqualTo(2);
        });
    }
}
