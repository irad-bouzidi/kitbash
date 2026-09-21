package com.example.demo.adapter.outbound.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The database row, and nothing else.
 *
 * <p>Named {@code Row} rather than {@code Entity} on purpose: in a hexagonal project the word
 * "entity" already belongs to {@code domain.Widget}, and two classes competing for it is how the
 * mapping quietly stops happening and JPA annotations appear on the domain a release later.
 */
@Entity
@Table(name = "widgets")
class WidgetRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WidgetRow() {
        // Required by JPA.
    }

    WidgetRow(Long id, String name, int quantity, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.quantity = quantity;
        this.createdAt = createdAt;
    }

    Long id() {
        return id;
    }

    String name() {
        return name;
    }

    int quantity() {
        return quantity;
    }

    Instant createdAt() {
        return createdAt;
    }
}
