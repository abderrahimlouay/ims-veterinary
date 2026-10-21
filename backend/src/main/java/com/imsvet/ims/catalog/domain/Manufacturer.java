package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Manufacturer (laboratory) of products.
 */
@Entity
@Table(name = "manufacturers")
public class Manufacturer extends ReferenceEntry {

    @Column(length = ReferenceList.COUNTRY_MAX_LENGTH)
    private String country;

    protected Manufacturer() {
    }

    public Manufacturer(String name, String country) {
        super(name);
        this.country = country;
    }

    @Override
    public String country() {
        return country;
    }

    @Override
    public void rename(String newName, String newCountry) {
        super.rename(newName, newCountry);
        this.country = newCountry;
    }
}
