package com.imsvet.ims.settings.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.settings.domain.AppSetting;
import com.imsvet.ims.settings.domain.SettingDefinition;
import com.imsvet.ims.settings.infrastructure.AppSettingRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and changing settings (FR-SEC-003, permission {@code settings.manage}). Each change is validated
 * against its format, normalised and audited as {@code SETTING_CHANGE} in the same transaction
 * (BR-AUDIT-001, BR-AUDIT-003). Currency and time zone are read-only (ADR-044).
 */
@Service
public class SettingsAdministration {

    private final AppSettingRepository repository;
    private final AuditRecorder audit;
    private final Clock clock;

    public SettingsAdministration(AppSettingRepository repository, AuditRecorder audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return repository.findAllByOrderByKeyAsc().stream().map(SettingsAdministration::toView).toList();
    }

    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional(readOnly = true)
    public SettingView get(String key) {
        return toView(find(key));
    }

    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public SettingView update(String key, String value) {
        AppSetting setting = find(key);
        SettingDefinition definition = SettingDefinition.byKey(key).orElseThrow();
        if (!definition.editable()) {
            throw invalid("READ_ONLY", "This setting changes only through a database migration.");
        }
        String normalized;
        try {
            normalized = definition.format().normalize(value);
        } catch (IllegalArgumentException e) {
            throw invalid("INVALID_FORMAT", e.getMessage());
        }
        String previous = setting.value();
        if (!previous.equals(normalized)) {
            setting.change(normalized, clock.instant());
            audit.record(AuditAction.SETTING_CHANGE, "app_settings", null,
                    Map.of("key", key, "value", previous), Map.of("key", key, "value", normalized));
        }
        return toView(setting);
    }

    private AppSetting find(String key) {
        return repository.findById(key)
                .filter(s -> SettingDefinition.byKey(s.key()).isPresent())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Unknown setting.",
                        Map.of("key", key)));
    }

    private static ValidationException invalid(String code, String message) {
        return new ValidationException(message, List.of(new Violation("value", code, message)));
    }

    private static SettingView toView(AppSetting s) {
        boolean editable = SettingDefinition.byKey(s.key()).map(SettingDefinition::editable).orElse(false);
        return new SettingView(s.key(), s.value(), s.description(), editable, s.updatedAt());
    }
}
