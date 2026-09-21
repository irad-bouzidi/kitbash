package com.example.demo.service

class DuplicateWidgetNameException(
    name: String,
) : RuntimeException("A widget named '$name' already exists")
