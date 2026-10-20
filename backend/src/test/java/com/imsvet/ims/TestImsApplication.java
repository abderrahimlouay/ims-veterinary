package com.imsvet.ims;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against a throw-away MySQL container: {@code ./mvnw spring-boot:test-run}.
 */
public class TestImsApplication {

    public static void main(String[] args) {
        SpringApplication.from(ImsApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
