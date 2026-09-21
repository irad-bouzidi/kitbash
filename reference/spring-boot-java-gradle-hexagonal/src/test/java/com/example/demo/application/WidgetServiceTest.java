package com.example.demo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.demo.application.port.outbound.WidgetRepository;
import com.example.demo.domain.DuplicateWidgetNameException;
import com.example.demo.domain.Widget;
import com.example.demo.domain.WidgetNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The rules the use cases enforce, tested without a database or a Spring context.
 *
 * <p>The mock here is of a port this package declared, not of a Spring Data interface — so the
 * test is written against the application's own vocabulary and would survive replacing JPA
 * outright. That is the payoff hexagonal is claiming, made concrete.
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
        Widget widget = Widget.existing(1L, "flux capacitor", 2, java.time.Instant.now());
        when(widgets.findById(1L)).thenReturn(Optional.of(widget));
        when(widgets.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.restock(1L, 5).quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("a negative restock is a programming error, not a quiet decrement")
    void rejectsNegativeRestock() {
        Widget widget = Widget.existing(1L, "flux capacitor", 2, java.time.Instant.now());
        when(widgets.findById(1L)).thenReturn(Optional.of(widget));

        assertThatThrownBy(() -> service.restock(1L, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(widget.quantity()).isEqualTo(2);
        verify(widgets, never()).save(any());
    }
}
