package com.example.demo.widget.internal;

class DuplicateWidgetNameException extends RuntimeException {

    DuplicateWidgetNameException(String name) {
        super("A widget named '" + name + "' already exists");
    }
}
