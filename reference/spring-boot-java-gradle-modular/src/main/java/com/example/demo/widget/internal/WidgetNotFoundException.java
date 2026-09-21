package com.example.demo.widget.internal;

class WidgetNotFoundException extends RuntimeException {

    WidgetNotFoundException(long id) {
        super("No widget with id " + id);
    }
}
