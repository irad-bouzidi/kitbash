package com.example.demo.service

class WidgetNotFoundException(
    id: Long,
) : RuntimeException("No widget with id $id")
