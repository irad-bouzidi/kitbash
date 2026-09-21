package com.example.demo.domain;

public class DuplicateWidgetNameException extends RuntimeException {

    public DuplicateWidgetNameException(String name) {
        super("A widget named '" + name + "' already exists");
    }
}
