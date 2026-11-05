package com.imsvet.ims.backup.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code ims.backup.*} from the environment (ADR-144). */
@Configuration
@EnableConfigurationProperties(BackupProperties.class)
class BackupConfiguration {
}
