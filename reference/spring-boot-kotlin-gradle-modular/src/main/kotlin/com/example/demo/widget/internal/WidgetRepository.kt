package com.example.demo.widget.internal

import org.springframework.data.jpa.repository.JpaRepository

internal interface WidgetRepository : JpaRepository<Widget, Long> {
    fun findByName(name: String): Widget?

    fun existsByName(name: String): Boolean
}
