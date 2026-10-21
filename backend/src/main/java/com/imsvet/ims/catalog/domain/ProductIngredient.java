package com.imsvet.ims.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * One active ingredient of a product with its optional strength ("500 mg").
 */
@Embeddable
public class ProductIngredient {

    public static final int STRENGTH_MAX_LENGTH = 60;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingredient_id")
    private ActiveIngredient ingredient;

    @Column(length = STRENGTH_MAX_LENGTH)
    private String strength;

    protected ProductIngredient() {
    }

    public ProductIngredient(ActiveIngredient ingredient, String strength) {
        this.ingredient = ingredient;
        this.strength = strength;
    }

    public ActiveIngredient ingredient() {
        return ingredient;
    }

    public String strength() {
        return strength;
    }
}
