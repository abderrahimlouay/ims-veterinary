package com.imsvet.ims.settings.api;

import com.imsvet.ims.settings.application.PublicSettingsQuery;
import com.imsvet.ims.settings.application.PublicSettingsView;
import com.imsvet.ims.settings.application.SettingView;
import com.imsvet.ims.settings.application.SettingsAdministration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4.14 settings; permissions on the services ({@code settings.manage}, public: authenticated).
 */
@RestController
@RequestMapping("/api/settings")
class SettingsController {

    record UpdateSettingRequest(@NotNull @Size(max = 200) String value) {
    }

    private final SettingsAdministration administration;
    private final PublicSettingsQuery publicSettings;

    SettingsController(SettingsAdministration administration, PublicSettingsQuery publicSettings) {
        this.administration = administration;
        this.publicSettings = publicSettings;
    }

    @GetMapping("/public")
    PublicSettingsView publicSettings() {
        return publicSettings.get();
    }

    @GetMapping
    List<SettingView> list() {
        return administration.list();
    }

    @GetMapping("/{key}")
    SettingView get(@PathVariable String key) {
        return administration.get(key);
    }

    @PutMapping("/{key}")
    SettingView update(@PathVariable String key, @Valid @RequestBody UpdateSettingRequest body) {
        return administration.update(key, body.value());
    }
}
