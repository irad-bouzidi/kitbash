package com.example.demo.application.port.inbound

import com.example.demo.domain.Widget

/**
 * What the application can be asked to do, stated without reference to who asks.
 *
 * A driving port. The web adapter depends on this interface and not on the class that implements
 * it, which is what makes "the HTTP layer is one of several possible front doors" a structural
 * fact rather than an aspiration.
 *
 * `inbound`/`outbound` rather than hexagonal's usual `in`/`out`, and the reason is this file's
 * language: `in` is a Kotlin hard keyword, and the only way to spell it as a package segment is
 * backticks, which ktlint rejects outright. Java would have taken `in` happily — which is exactly
 * why the choice is made once, for both languages, instead of letting the two trees diverge on a
 * detail nobody would remember.
 */
interface ManageWidgets {
    fun findAll(): List<Widget>

    fun findById(id: Long): Widget

    fun create(
        name: String,
        quantity: Int,
    ): Widget

    fun restock(
        id: Long,
        amount: Int,
    ): Widget

    fun delete(id: Long)
}
