package com.example.demo.service;

public class WidgetNotFoundException extends RuntimeException {

    public WidgetNotFoundException(long id) {
        super("No widget with id " + id);
    }
}
