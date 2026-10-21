package com.imsvet.ims.users.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AdministratorBootstrap} at start-up when {@code IMS_BOOTSTRAP_ADMIN_USERNAME} and
 * {@code IMS_BOOTSTRAP_ADMIN_PASSWORD} are both set (ADR-040).
 */
@Component
public class AdministratorBootstrapRunner implements ApplicationRunner {

    private final AdministratorBootstrap bootstrap;
    private final String username;
    private final String password;
    private final String fullName;

    public AdministratorBootstrapRunner(AdministratorBootstrap bootstrap,
            @Value("${ims.bootstrap.admin.username}") String username,
            @Value("${ims.bootstrap.admin.password}") String password,
            @Value("${ims.bootstrap.admin.full-name}") String fullName) {
        this.bootstrap = bootstrap;
        this.username = username;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!username.isBlank() && !password.isBlank()) {
            bootstrap.createIfNoUsers(username.trim(), password, fullName);
        }
    }
}
