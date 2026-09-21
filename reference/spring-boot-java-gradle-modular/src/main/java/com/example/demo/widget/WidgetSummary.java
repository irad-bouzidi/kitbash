package com.example.demo.widget;

/**
 * What one widget looks like to a module that is not the widget module.
 *
 * <p>A record of plain values rather than the entity, which is the point: another module holding
 * a managed entity could write to this module's tables through it, and that is a shared database
 * with extra steps.
 */
public record WidgetSummary(Long id, String name, int quantity) {}
