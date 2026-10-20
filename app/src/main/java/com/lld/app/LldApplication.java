package com.lld.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Single entry point; every topic's adapter lives in a sub-package of {@code com.lld.app}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LldApplication {

    public static void main(String[] args) {
        SpringApplication.run(LldApplication.class, args);
    }
}
