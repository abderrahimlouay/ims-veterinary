package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * One entry of a reference list. Seeded entries have a {@link #code()} and are protected (ADR-054).
 */
@MappedSuperclass
public abstract class ReferenceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    protected ReferenceEntry() {
    }

    protected ReferenceEntry(String name) {
        this.name = name;
    }

    public Integer id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** {@code null} for entries added by an administrator. */
    public String code() {
        return null;
    }

    /** Only manufacturers have a country. */
    public String country() {
        return null;
    }

    public boolean seeded() {
        return code() != null;
    }

    /** {@code country} is ignored by lists without one. */
    public void rename(String newName, String newCountry) {
        this.name = newName;
    }
}
