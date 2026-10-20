package com.imsvet.ims;

import java.time.ZoneOffset;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ImsApplication {

    public static void main(String[] args) {
        // ADR-010: the JVM runs on UTC; business-calendar logic uses the injected Clock and business zone.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(ImsApplication.class, args);
    }
}
