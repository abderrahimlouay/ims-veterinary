package com.imsvet.ims.settings.application;

import com.imsvet.ims.settings.domain.AppSetting;
import com.imsvet.ims.settings.domain.SettingDefinition;
import com.imsvet.ims.settings.infrastructure.AppSettingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fails the start-up when the stored settings and {@link SettingDefinition} disagree (a missing, unknown or
 * malformed key is a migration defect, ADR-044), instead of failing later in a business operation.
 */
@Component
public class SettingsConsistencyCheck implements ApplicationRunner {

    private final AppSettingRepository repository;

    public SettingsConsistencyCheck(AppSettingRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Application settings are inconsistent: " + problems);
        }
    }

    List<String> problems() {
        Map<String, AppSetting> stored = repository.findAllByOrderByKeyAsc().stream()
                .collect(Collectors.toMap(AppSetting::key, Function.identity()));
        List<String> problems = new ArrayList<>();
        for (SettingDefinition definition : SettingDefinition.values()) {
            AppSetting setting = stored.remove(definition.key());
            if (setting == null) {
                problems.add("missing " + definition.key());
            } else {
                try {
                    if (!definition.format().normalize(setting.value()).equals(setting.value())) {
                        problems.add("non-canonical " + definition.key());
                    }
                } catch (IllegalArgumentException e) {
                    problems.add("malformed " + definition.key());
                }
            }
        }
        stored.keySet().forEach(key -> problems.add("unknown " + key));
        return problems;
    }
}
