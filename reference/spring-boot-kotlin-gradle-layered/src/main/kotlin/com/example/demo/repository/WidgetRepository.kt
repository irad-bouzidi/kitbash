package com.example.demo.repository

import com.example.demo.domain.Widget
import org.springframework.data.jpa.repository.JpaRepository

interface WidgetRepository : JpaRepository<Widget, Long> {
    fun findByName(name: String): Widget?

    fun existsByName(name: String): Boolean
}
