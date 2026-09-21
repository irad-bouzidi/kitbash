package com.example.demo.adapter.outbound.persistence

import com.example.demo.application.port.outbound.WidgetRepository
import com.example.demo.domain.Widget
import org.springframework.stereotype.Component

/**
 * The driven adapter: it implements a port the application declared, and it is the only class in
 * the project that knows both a [Widget] and a [WidgetRow].
 *
 * The mapping is hand-written and that is the honest cost of the architecture. Two functions, no
 * mapping framework, and a translation anyone can read — which is a better trade at this size than
 * a library that has to be configured before a reader can tell what a field becomes.
 */
@Component
internal class WidgetPersistenceAdapter(
    private val rows: WidgetJpaRepository,
) : WidgetRepository {
    override fun findAll(): List<Widget> = rows.findAll().map { it.toDomain() }

    override fun findById(id: Long): Widget? = rows.findById(id).map { it.toDomain() }.orElse(null)

    override fun existsByName(name: String): Boolean = rows.existsByName(name)

    override fun existsById(id: Long): Boolean = rows.existsById(id)

    override fun save(widget: Widget): Widget = rows.save(WidgetRow(widget.id, widget.name, widget.quantity, widget.createdAt)).toDomain()

    override fun deleteById(id: Long) = rows.deleteById(id)

    private fun WidgetRow.toDomain(): Widget = Widget.existing(id!!, name, quantity, createdAt)
}
