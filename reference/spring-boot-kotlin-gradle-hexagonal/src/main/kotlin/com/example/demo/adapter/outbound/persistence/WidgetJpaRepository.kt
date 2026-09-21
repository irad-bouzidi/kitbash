package com.example.demo.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data's view of the table. `internal`: only the adapter beside it may use it. */
internal interface WidgetJpaRepository : JpaRepository<WidgetRow, Long> {
    fun existsByName(name: String): Boolean
}
