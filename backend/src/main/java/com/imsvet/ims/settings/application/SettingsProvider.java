package com.imsvet.ims.settings.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.settings.domain.AppSetting;
import com.imsvet.ims.settings.domain.SettingDefinition;
import com.imsvet.ims.settings.infrastructure.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Typed read access to application settings for other modules (backend.md §3). A missing or malformed
 * seeded setting is a deployment defect, not a user error.
 */
@Service
@InternalService
public class SettingsProvider {

    private final AppSettingRepository repository;

    public SettingsProvider(AppSettingRepository repository) {
        this.repository = repository;
    }

    /** The stored, normalised value of a defined setting. */
    @Transactional(readOnly = true)
    public String value(SettingDefinition definition) {
        return repository.findById(definition.key()).map(AppSetting::value)
                .orElseThrow(() -> new IllegalStateException("Missing setting " + definition.key()));
    }

    @Transactional(readOnly = true)
    public int intValue(String key) {
        String value = repository.findById(key).map(AppSetting::value)
                .orElseThrow(() -> new IllegalStateException("Missing setting " + key));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Setting " + key + " is not an integer", e);
        }
    }
}
