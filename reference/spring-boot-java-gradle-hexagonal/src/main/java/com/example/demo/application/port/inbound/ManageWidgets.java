package com.example.demo.application.port.inbound;

import com.example.demo.domain.Widget;
import java.util.List;

/**
 * What the application can be asked to do, stated without reference to who asks.
 *
 * <p>{@code inbound}/{@code outbound} rather than hexagonal's usual {@code in}/{@code out}: the
 * Kotlin backend emits the same structure, {@code in} is a Kotlin hard keyword, and the only way
 * to spell it as a package segment there is backticks — which ktlint rejects. Java would have
 * taken {@code in} happily; the choice is made once, for both languages, rather than letting two
 * trees for the same architecture diverge on a detail nobody would remember.
 *
 * <p>A driving port. The web adapter depends on this interface and not on the class that
 * implements it, which is what makes "the HTTP layer is one of several possible front doors" a
 * structural fact rather than an aspiration — a CLI or a message consumer is a second adapter
 * against this same interface and no change to anything behind it.
 */
public interface ManageWidgets {

    List<Widget> findAll();

    Widget findById(long id);

    Widget create(String name, int quantity);

    Widget restock(long id, int amount);

    void delete(long id);
}
