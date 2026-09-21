package com.example.demo.application

import com.example.demo.application.port.inbound.ManageWidgets
import com.example.demo.application.port.outbound.WidgetRepository
import com.example.demo.domain.DuplicateWidgetNameException
import com.example.demo.domain.Widget
import com.example.demo.domain.WidgetNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The use cases. Business rules live here and in the domain, never in an adapter.
 *
 * This class is annotated — `@Service`, `@Transactional` — and that is deliberate rather than a
 * leak. A transaction boundary is a statement about a use case, so it belongs on the use case.
 * What must stay clean is the *domain*, and `ArchitectureTest` enforces exactly that line rather
 * than a slogan about framework-free code.
 */
@Service
@Transactional(readOnly = true)
class WidgetService(
    private val widgets: WidgetRepository,
) : ManageWidgets {
    override fun findAll(): List<Widget> = widgets.findAll()

    override fun findById(id: Long): Widget = widgets.findById(id) ?: throw WidgetNotFoundException(id)

    @Transactional
    override fun create(
        name: String,
        quantity: Int,
    ): Widget {
        if (widgets.existsByName(name)) {
            throw DuplicateWidgetNameException(name)
        }
        return widgets.save(Widget.of(name, quantity))
    }

    @Transactional
    override fun restock(
        id: Long,
        amount: Int,
    ): Widget {
        val widget = findById(id)
        widget.restock(amount)
        // Explicit, unlike the layered version: a domain object is not a managed entity here, so
        // nothing writes it back for us at commit. That is the trade hexagonal makes.
        return widgets.save(widget)
    }

    @Transactional
    override fun delete(id: Long) {
        if (!widgets.existsById(id)) {
            throw WidgetNotFoundException(id)
        }
        widgets.deleteById(id)
    }
}
