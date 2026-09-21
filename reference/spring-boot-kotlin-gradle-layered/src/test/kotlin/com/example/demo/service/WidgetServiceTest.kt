package com.example.demo.service

import com.example.demo.domain.Widget
import com.example.demo.repository.WidgetRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

/**
 * The rules the service enforces, tested without a database or a Spring context.
 *
 * `BDDMockito.given` rather than `Mockito.when`: `when` is a Kotlin keyword, and the alternative
 * is backticks on every stub line.
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

    @Test
    @DisplayName("creating a widget whose name is taken is rejected before it reaches the database")
    fun rejectsDuplicateName() {
        given(widgets.existsByName("flux capacitor")).willReturn(true)

        assertThatThrownBy { service.create("flux capacitor", 1) }
            .isInstanceOf(DuplicateWidgetNameException::class.java)
            .hasMessageContaining("flux capacitor")

        verify(widgets, never()).save(any())
    }

    @Test
    @DisplayName("fetching an unknown id reports the id that was not found")
    fun reportsMissingId() {
        given(widgets.findById(404L)).willReturn(Optional.empty())

        assertThatThrownBy { service.findById(404L) }
            .isInstanceOf(WidgetNotFoundException::class.java)
            .hasMessageContaining("404")
    }

    @Test
    @DisplayName("restocking adds to the existing quantity")
    fun restockAdds() {
        val widget = Widget.of("flux capacitor", 2)
        given(widgets.findById(1L)).willReturn(Optional.of(widget))

        assertThat(service.restock(1L, 5).quantity).isEqualTo(7)
    }

    @Test
    @DisplayName("a negative restock is a programming error, not a quiet decrement")
    fun rejectsNegativeRestock() {
        val widget = Widget.of("flux capacitor", 2)
        given(widgets.findById(1L)).willReturn(Optional.of(widget))

        assertThatThrownBy { service.restock(1L, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(widget.quantity).isEqualTo(2)
    }
}
