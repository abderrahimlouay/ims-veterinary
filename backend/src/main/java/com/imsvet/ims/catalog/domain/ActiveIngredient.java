package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Active ingredient (DCI / INN), for search and substitution (FR-PROD-004).
 */
@Entity
@Table(name = "active_ingredients")
public class ActiveIngredient extends ReferenceEntry {

    protected ActiveIngredient() {
    }

    public ActiveIngredient(String name) {
        super(name);
    }
}
