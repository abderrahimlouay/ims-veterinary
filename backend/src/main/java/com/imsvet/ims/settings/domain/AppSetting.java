package com.imsvet.ims.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One application setting (database.md §3.1, FR-SEC-003). Values are text; each key has a known format.
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 80)
    private String key;

    @Column(name = "setting_value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppSetting() {
    }

    /** Stores an already validated, normalised value. */
    public void change(String newValue, Instant at) {
        this.value = newValue;
        this.updatedAt = at;
    }

    public String key() {
        return key;
    }

    public String value() {
        return value;
    }

    public String description() {
        return description;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
