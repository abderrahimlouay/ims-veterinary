package com.imsvet.ims.catalog.application;

/**
 * BR-UNIT-004: tells whether document lines reference a packaging unit. Modules owning document lines with a
 * {@code unit_id} (purchasing and sales, from Phase 5) provide an implementation; until then no unit is in use.
 */
public interface UnitReferences {

    boolean referencesUnit(long unitId);
}
