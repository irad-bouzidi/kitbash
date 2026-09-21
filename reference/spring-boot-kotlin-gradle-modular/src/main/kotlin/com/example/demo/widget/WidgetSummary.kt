package com.example.demo.widget

/**
 * What one widget looks like to a module that is not the widget module.
 *
 * Plain values rather than the entity, which is the point: another module holding a managed entity
 * could write to this module's tables through it.
 */
data class WidgetSummary(
    val id: Long?,
    val name: String,
    val quantity: Int,
)
