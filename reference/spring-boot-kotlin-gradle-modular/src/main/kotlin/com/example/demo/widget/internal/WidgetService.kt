package com.example.demo.widget.internal

import com.example.demo.widget.WidgetApi
import com.example.demo.widget.WidgetSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Business rules live here, not in the controller and not in the entity.
 *
 * It also implements [WidgetApi], which is how the module's published surface and its internal one
 * stay the same code: the alternative — a separate facade that delegates — is two places to change
 * and a standing invitation to let them drift.
 */
@Service
@Transactional(readOnly = true)
internal class WidgetService(
    private val widgets: WidgetRepository,
) : WidgetApi {
    override fun summaries(): List<WidgetSummary> = widgets.findAll().map { WidgetSummary(it.id, it.name, it.quantity) }

    fun findAll(): List<Widget> = widgets.findAll()

    fun findById(id: Long): Widget = widgets.findById(id).orElseThrow { WidgetNotFoundException(id) }

    @Transactional
    fun create(
        name: String,
        quantity: Int,
    ): Widget {
        if (widgets.existsByName(name)) {
            throw DuplicateWidgetNameException(name)
        }
        return widgets.save(Widget.of(name, quantity))
    }

    @Transactional
    fun restock(
        id: Long,
        amount: Int,
    ): Widget {
        val widget = findById(id)
        widget.restock(amount)
        return widget
    }

    @Transactional
    fun delete(id: Long) {
        if (!widgets.existsById(id)) {
            throw WidgetNotFoundException(id)
        }
        widgets.deleteById(id)
    }
}
