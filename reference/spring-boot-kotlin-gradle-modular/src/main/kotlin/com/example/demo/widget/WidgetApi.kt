package com.example.demo.widget

/**
 * Everything another module may ask of this one.
 *
 * This interface and [WidgetSummary] are the only public types the widget module has; everything
 * else lives in `widget.internal` and is `internal` besides. That is what makes this a modular
 * monolith rather than a monolith with folders: `ArchitectureTest` fails the build if anything
 * outside the module reaches past this file, so a shortcut through the repository is not something
 * a reviewer has to notice.
 *
 * The return type is deliberately not the entity. Publishing a JPA entity would let another module
 * hold a reference to a managed object and write to this module's tables through it, which is a
 * shared database with extra steps.
 */
interface WidgetApi {
    fun summaries(): List<WidgetSummary>
}
