package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Dosage form (injectable, tablet…).
 */
@Entity
@Table(name = "dosage_forms")
public class DosageForm extends ReferenceEntry {

    @Column(length = 40, updatable = false)
    private String code;

    protected DosageForm() {
    }

    public DosageForm(String name) {
        super(name);
    }

    @Override
    public String code() {
        return code;
    }
}
