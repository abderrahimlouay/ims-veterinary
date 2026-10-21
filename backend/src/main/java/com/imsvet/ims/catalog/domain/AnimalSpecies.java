package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Animal species, for withdrawal periods and target species.
 */
@Entity
@Table(name = "animal_species")
public class AnimalSpecies extends ReferenceEntry {

    @Column(length = 40, updatable = false)
    private String code;

    protected AnimalSpecies() {
    }

    public AnimalSpecies(String name) {
        super(name);
    }

    @Override
    public String code() {
        return code;
    }
}
