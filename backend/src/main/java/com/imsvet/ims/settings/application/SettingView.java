package com.imsvet.ims.settings.application;

import java.time.Instant;

/**
 * One setting as shown to administrators (api.md §4.14).
 */
public record SettingView(String key, String value, String description, boolean editable, Instant updatedAt) {
}
