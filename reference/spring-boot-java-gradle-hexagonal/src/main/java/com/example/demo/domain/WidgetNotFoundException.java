package com.example.demo.domain;

public class WidgetNotFoundException extends RuntimeException {

    public WidgetNotFoundException(long id) {
        super("No widget with id " + id);
    }
}
