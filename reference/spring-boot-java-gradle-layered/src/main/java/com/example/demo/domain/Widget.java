package com.example.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * The example entity. One aggregate is enough to show the shape of a slice: an empty skeleton
 * teaches nothing, and a second entity would only repeat what this one already shows.
 */
@Entity
@Table(name = "widgets")
public class Widget {

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

    public static Widget of(String name, int quantity) {
        return new Widget(name, quantity, Instant.now());
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int quantity() {
        return quantity;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void restock(int amount) {
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
