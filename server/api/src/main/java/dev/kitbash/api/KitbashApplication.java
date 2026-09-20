package dev.kitbash.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling is on for one job: §10's nightly retention sweep. It is enabled here rather than in
// that job's own configuration so there is one place to look for "what runs on a timer".
@EnableScheduling
@SpringBootApplication
public class KitbashApplication {

    public static void main(String[] args) {
        SpringApplication.run(KitbashApplication.class, args);
    }
}
