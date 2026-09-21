package com.example.demo.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The example entity, and the one class in this project with no framework on it at all.
 *
 * <p>That is the whole claim hexagonal makes, and `ArchitectureTest` is what keeps it true: no
 * Spring, no Jakarta Persistence, no Jackson. The mapping to a database row lives in
 * {@code adapter.outbound.persistence}, which is free to be as annotated as JPA needs.
 *
 * <p>The cost is one mapping class. The return is that the rules below can be read, tested and
 * changed without starting a context or a database — and that swapping the persistence adapter
 * is a change in one package.
 */
public final class Widget {

    private final Long id;
    private String name;
    private int quantity;
    private final Instant createdAt;

    private Widget(Long id, String name, int quantity, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.quantity = quantity;
        this.createdAt = createdAt;
    }

    /** A widget that has never been stored: no id yet, and the clock read once, here. */
    public static Widget of(String name, int quantity) {
        return new Widget(null, name, quantity, Instant.now());
    }

    /** A widget read back from storage. Used by the persistence adapter, not by callers. */
    public static Widget existing(Long id, String name, int quantity, Instant createdAt) {
        return new Widget(id, name, quantity, createdAt);
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
        // Identity is the stored id; two unsaved widgets are never equal.
        return other instanceof Widget widget && id != null && Objects.equals(id, widget.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
