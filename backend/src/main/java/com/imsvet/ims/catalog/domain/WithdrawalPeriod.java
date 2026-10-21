package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * Withdrawal days for meat, milk and eggs of one species (FR-PROD-005); {@code null} means "not specified",
 * never zero (BR-PROD-007).
 */
@Embeddable
public class WithdrawalPeriod {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "species_id")
    private AnimalSpecies species;

    @Column(name = "meat_days")
    private Short meatDays;

    @Column(name = "milk_days")
    private Short milkDays;

    @Column(name = "eggs_days")
    private Short eggsDays;

    protected WithdrawalPeriod() {
    }

    public WithdrawalPeriod(AnimalSpecies species, Short meatDays, Short milkDays, Short eggsDays) {
        this.species = species;
        this.meatDays = meatDays;
        this.milkDays = milkDays;
        this.eggsDays = eggsDays;
    }

    public AnimalSpecies species() {
        return species;
    }

    public Short meatDays() {
        return meatDays;
    }

    public Short milkDays() {
        return milkDays;
    }

    public Short eggsDays() {
        return eggsDays;
    }
}
