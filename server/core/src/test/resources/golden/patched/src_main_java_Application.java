package com.acme.customer;

import org.springframework.boot.SpringApplication;
// kitbash:imports
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
