package com.imsvet.ims.catalog.application;

import com.imsvet.ims.catalog.domain.ActiveIngredient;
import com.imsvet.ims.catalog.domain.AnimalSpecies;
import com.imsvet.ims.catalog.domain.DosageForm;
import com.imsvet.ims.catalog.domain.Manufacturer;
import com.imsvet.ims.catalog.domain.ReferenceEntry;
import com.imsvet.ims.catalog.domain.ReferenceList;
import com.imsvet.ims.catalog.domain.TherapeuticCategory;
import com.imsvet.ims.catalog.infrastructure.ActiveIngredientRepository;
import com.imsvet.ims.catalog.infrastructure.AnimalSpeciesRepository;
import com.imsvet.ims.catalog.infrastructure.DosageFormRepository;
import com.imsvet.ims.catalog.infrastructure.ManufacturerRepository;
import com.imsvet.ims.catalog.infrastructure.ReferenceRepository;
import com.imsvet.ims.catalog.infrastructure.TherapeuticCategoryRepository;
import com.imsvet.ims.common.error.BusinessException;
import com.imsvet.ims.common.error.ErrorCode;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalogue reference lists (FR-PROD-008). Names are unique ignoring case and accents (ADR-051);
 * seeded entries can be neither renamed nor deleted (ADR-054); an entry still referenced cannot be deleted
 * (ADR-053) — detected by the database foreign keys, so every referencing table is covered.
 */
@Service
public class ReferenceLists {

    private record Store<T extends ReferenceEntry>(ReferenceRepository<T> repository,
            BiFunction<String, String, T> factory) {
    }

    private final Map<ReferenceList, Store<?>> stores = new EnumMap<>(ReferenceList.class);

    public ReferenceLists(TherapeuticCategoryRepository categories, ManufacturerRepository manufacturers,
            DosageFormRepository dosageForms, ActiveIngredientRepository ingredients, AnimalSpeciesRepository species) {
        stores.put(ReferenceList.CATEGORIES, new Store<>(categories, (name, country) -> new TherapeuticCategory(name)));
        stores.put(ReferenceList.MANUFACTURERS, new Store<>(manufacturers, Manufacturer::new));
        stores.put(ReferenceList.DOSAGE_FORMS, new Store<>(dosageForms, (name, country) -> new DosageForm(name)));
        stores.put(ReferenceList.ACTIVE_INGREDIENTS, new Store<>(ingredients, (name, country) -> new ActiveIngredient(name)));
        stores.put(ReferenceList.ANIMAL_SPECIES, new Store<>(species, (name, country) -> new AnimalSpecies(name)));
    }

    @PreAuthorize("hasAuthority('product.read')")
    @Transactional(readOnly = true)
    public List<ReferenceItemView> list(ReferenceList list) {
        return stores.get(list).repository().findAllByOrderByNameAsc().stream().map(ReferenceLists::toView).toList();
    }

    @PreAuthorize("hasAuthority('reference.manage')")
    @Transactional
    public ReferenceItemView create(ReferenceList list, String name, String country) {
        return create(stores.get(list), normalise(list, name, country), trimToNull(country));
    }

    @PreAuthorize("hasAuthority('reference.manage')")
    @Transactional
    public ReferenceItemView rename(ReferenceList list, int id, String name, String country) {
        return rename(stores.get(list), list, id, normalise(list, name, country), trimToNull(country));
    }

    @PreAuthorize("hasAuthority('reference.manage')")
    @Transactional
    public void delete(ReferenceList list, int id) {
        delete(stores.get(list), id);
    }

    private <T extends ReferenceEntry> ReferenceItemView create(Store<T> store, String name, String country) {
        rejectDuplicate(store, name, null);
        try {
            return toView(store.repository().saveAndFlush(store.factory().apply(name, country)));
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    private <T extends ReferenceEntry> ReferenceItemView rename(Store<T> store, ReferenceList list, int id,
            String name, String country) {
        T entry = find(store, id);
        protectSeeded(entry);
        rejectDuplicate(store, name, entry.id());
        entry.rename(name, list == ReferenceList.MANUFACTURERS ? country : null);
        try {
            return toView(store.repository().saveAndFlush(entry));
        } catch (DataIntegrityViolationException e) {
            throw duplicate();
        }
    }

    private <T extends ReferenceEntry> void delete(Store<T> store, int id) {
        T entry = find(store, id);
        protectSeeded(entry);
        try {
            store.repository().delete(entry);
            store.repository().flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.RESOURCE_IN_USE, "This entry is used and cannot be deleted.",
                    Map.of("id", id));
        }
    }

    private static <T extends ReferenceEntry> T find(Store<T> store, int id) {
        return store.repository().findById(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Reference entry not found.", Map.of("id", id)));
    }

    private static void protectSeeded(ReferenceEntry entry) {
        if (entry.seeded()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Seeded reference entries can be neither renamed nor deleted.", Map.of("code", entry.code()));
        }
    }

    private static <T extends ReferenceEntry> void rejectDuplicate(Store<T> store, String name, Integer self) {
        if (store.repository().findByName(name).filter(e -> !e.id().equals(self)).isPresent()) {
            throw duplicate();
        }
    }

    private static BusinessException duplicate() {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "An entry with this name already exists.",
                Map.of("field", "name"));
    }

    private static String normalise(ReferenceList list, String name, String country) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw invalid("name", "REQUIRED");
        }
        if (trimmed.length() > list.nameMaxLength()) {
            throw invalid("name", "SIZE");
        }
        if (country != null && country.trim().length() > ReferenceList.COUNTRY_MAX_LENGTH) {
            throw invalid("country", "SIZE");
        }
        return trimmed;
    }

    private static ValidationException invalid(String field, String code) {
        return new ValidationException("Invalid reference entry.", List.of(new Violation(field, code, field + " " + code)));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ReferenceItemView toView(ReferenceEntry e) {
        return new ReferenceItemView(e.id(), e.name(), e.code(), e.country());
    }
}
