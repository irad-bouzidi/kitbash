package com.example.demo.application.port.outbound

import com.example.demo.domain.Widget

/**
 * What the application needs from the outside world, stated without reference to who provides it.
 *
 * A driven port, and the direction of the dependency is the point: this interface is declared
 * *here*, by the code that needs it, rather than in the persistence package that satisfies it. So
 * the adapter depends on the application and never the other way round, which is what the
 * dependency-inversion half of hexagonal actually means.
 *
 * It speaks [Widget], not rows: a port that returned a JPA entity would put the database back in
 * the middle of the application under a different name.
 */
interface WidgetRepository {
    fun findAll(): List<Widget>

    fun findById(id: Long): Widget?

    fun existsByName(name: String): Boolean

    fun existsById(id: Long): Boolean

    fun save(widget: Widget): Widget

    fun deleteById(id: Long)
}
