package com.imsvet.ims.catalog.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The catalogue reference lists (FR-PROD-008, api.md §4.3): URL path and maximum name length (column size).
 */
public enum ReferenceList {

    CATEGORIES("categories", 100),
    MANUFACTURERS("manufacturers", 150),
    DOSAGE_FORMS("dosage-forms", 60),
    ACTIVE_INGREDIENTS("active-ingredients", 150),
    ANIMAL_SPECIES("animal-species", 60);

    public static final int COUNTRY_MAX_LENGTH = 80;

    private final String path;
    private final int nameMaxLength;

    ReferenceList(String path, int nameMaxLength) {
        this.path = path;
        this.nameMaxLength = nameMaxLength;
    }

    public static Optional<ReferenceList> fromPath(String path) {
        return Arrays.stream(values()).filter(l -> l.path.equals(path)).findFirst();
    }

    public String path() {
        return path;
    }

    public int nameMaxLength() {
        return nameMaxLength;
    }
}
