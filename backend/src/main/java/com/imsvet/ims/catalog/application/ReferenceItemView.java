package com.imsvet.ims.catalog.application;

/**
 * One reference entry (api.md §4.3). {@code code} is set for seeded entries only (the UI translates it,
 * ADR-054); {@code country} for manufacturers only.
 */
public record ReferenceItemView(int id, String name, String code, String country) {
}
