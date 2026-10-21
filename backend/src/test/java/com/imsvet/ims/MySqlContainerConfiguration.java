package com.imsvet.ims;

import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MySQL (ADR-030), same release line as production (ADR-032). Used alone by
 * {@link TestImsApplication}; tests get it through {@link TestcontainersConfiguration}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfiguration {

    public static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    /** Distinctive so tests can assert it never reaches the logs (ADR-031). */
    public static final String MYSQL_PASSWORD = "tc-canary-" + UUID.randomUUID();

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        // ADR-043: binary logging is on by default; this lets the non-SUPER user create triggers (ADR-017).
        return new MySQLContainer(MYSQL_IMAGE).withPassword(MYSQL_PASSWORD)
                .withCommand("--log-bin-trust-function-creators=1");
    }
}
