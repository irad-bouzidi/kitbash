package com.example.demo.widget.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * The example entity. Package-private on purpose: it is this module's model of its own data, and
 * nothing outside the module has any business naming it.
 */
@Entity
@Table(name = "widgets")
class Widget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Widget() {
        // Required by JPA. Application code uses the factory below.
    }

    private Widget(String name, int quantity, Instant createdAt) {
        this.name = name;
        this.quantity = quantity;
        this.createdAt = createdAt;
    }

    static Widget of(String name, int quantity) {
        return new Widget(name, quantity, Instant.now());
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

    void rename(String newName) {
        this.name = newName;
    }

    void restock(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("restock amount must not be negative: " + amount);
        }
        this.quantity += amount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Identity is the database id; two unsaved widgets are never equal.
        return other instanceof Widget widget && id != null && Objects.equals(id, widget.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
