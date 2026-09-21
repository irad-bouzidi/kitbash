package com.example.demo

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * One Postgres container shared by every integration test in the suite. It is started once and
 * deliberately not stopped: Ryuk reaps it when the JVM exits, and starting a container per test
 * class is the usual reason an integration suite becomes something nobody runs locally.
 */
abstract class PostgresTestBase {
    companion object {
        private val POSTGRES = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply { start() }

        // `@JvmStatic` is not optional: Spring looks up `@DynamicPropertySource` as a static
        // method, and a companion function without it compiles to an instance method on
        // `Companion` that the lookup never finds.
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { POSTGRES.jdbcUrl }
            registry.add("spring.datasource.username") { POSTGRES.username }
            registry.add("spring.datasource.password") { POSTGRES.password }
            registry.add("demo.environment-name") { "test" }
        }
    }
}
