package com.example.demo.service

import com.example.demo.domain.Widget
import com.example.demo.repository.WidgetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Business rules live here, not in the controller and not in the entity. The controller maps
 * HTTP to method calls; this class decides what is allowed.
 */
@Service
@Transactional(readOnly = true)
class WidgetService(
    private val widgets: WidgetRepository,
) {
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
