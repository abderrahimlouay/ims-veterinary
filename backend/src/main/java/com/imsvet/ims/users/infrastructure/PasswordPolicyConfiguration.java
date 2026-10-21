package com.imsvet.ims.users.infrastructure;

import com.imsvet.ims.users.domain.PasswordPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Builds the {@link PasswordPolicy} from {@code ims.security.password.min-length} and the bundled list
 * {@code security/common-passwords.txt} (ADR-039).
 */
@Configuration(proxyBeanMethods = false)
public class PasswordPolicyConfiguration {

    @Bean
    public PasswordPolicy passwordPolicy(@Value("${ims.security.password.min-length}") int minLength,
            @Value("classpath:security/common-passwords.txt") Resource commonPasswords) {
        try {
            Set<String> passwords = commonPasswords.getContentAsString(StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            return new PasswordPolicy(minLength, passwords);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the common-password list", e);
        }
    }
}
