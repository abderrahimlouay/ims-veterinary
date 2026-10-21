package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Therapeutic category (antibiotic, vaccine…).
 */
@Entity
@Table(name = "therapeutic_categories")
public class TherapeuticCategory extends ReferenceEntry {

    @Column(length = 40, updatable = false)
    private String code;

    protected TherapeuticCategory() {
    }

    public TherapeuticCategory(String name) {
        super(name);
    }

    @Override
    public String code() {
        return code;
    }
}
