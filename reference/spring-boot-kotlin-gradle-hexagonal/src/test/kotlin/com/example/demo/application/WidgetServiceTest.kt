package com.example.demo.application

import com.example.demo.application.port.outbound.WidgetRepository
import com.example.demo.domain.DuplicateWidgetNameException
import com.example.demo.domain.Widget
import com.example.demo.domain.WidgetNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant

/**
 * The rules the use cases enforce, tested without a database or a Spring context.
 *
 * The mock here is of a port this package declared, not of a Spring Data interface — so the test
 * is written against the application's own vocabulary and would survive replacing JPA outright.
 * That is the payoff hexagonal is claiming, made concrete.
 */
@ExtendWith(MockitoExtension::class)
class WidgetServiceTest {
    @Mock
    private lateinit var widgets: WidgetRepository

    private lateinit var service: WidgetService

    @BeforeEach
    fun setUp() {
        service = WidgetService(widgets)
    }

    /**
     * `ArgumentMatchers.any()` returns null, and the port this file mocks is declared in Kotlin, so
     * `save(widget: Widget)` null-checks its parameter before Mockito ever records the call. The
     * unchecked cast is the standard interop workaround and it is safe: a matcher is a marker
     * Mockito stores, never a value the method reads.
     *
     * The layered and modular projects do not need this, because there the mocked repository is
     * Spring Data's — a Java interface, with no null check to trip over. Declaring your own ports
     * is what brings the problem, and it arrives with the architecture rather than the language.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArgument(): T = ArgumentMatchers.any<T>() as T

    @Test
    @DisplayName("creating a widget whose name is taken is rejected before it reaches the database")
    fun rejectsDuplicateName() {
        given(widgets.existsByName("flux capacitor")).willReturn(true)

        assertThatThrownBy { service.create("flux capacitor", 1) }
            .isInstanceOf(DuplicateWidgetNameException::class.java)
            .hasMessageContaining("flux capacitor")

        verify(widgets, never()).save(anyArgument())
    }

    @Test
    @DisplayName("fetching an unknown id reports the id that was not found")
    fun reportsMissingId() {
        given(widgets.findById(404L)).willReturn(null)

        assertThatThrownBy { service.findById(404L) }
            .isInstanceOf(WidgetNotFoundException::class.java)
            .hasMessageContaining("404")
    }

    @Test
    @DisplayName("restocking adds to the existing quantity")
    fun restockAdds() {
        val widget = Widget.existing(1L, "flux capacitor", 2, Instant.now())
        given(widgets.findById(1L)).willReturn(widget)
        given(widgets.save(anyArgument())).willAnswer { it.getArgument(0) }

        assertThat(service.restock(1L, 5).quantity).isEqualTo(7)
    }

    @Test
    @DisplayName("a negative restock is a programming error, not a quiet decrement")
    fun rejectsNegativeRestock() {
        val widget = Widget.existing(1L, "flux capacitor", 2, Instant.now())
        given(widgets.findById(1L)).willReturn(widget)

        assertThatThrownBy { service.restock(1L, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(widget.quantity).isEqualTo(2)
        verify(widgets, never()).save(anyArgument())
    }
}
