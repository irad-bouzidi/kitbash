package com.example.demo.domain

class WidgetNotFoundException(
    id: Long,
) : RuntimeException("No widget with id $id")
