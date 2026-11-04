package com.imsvet.ims.settings.infrastructure;

import com.imsvet.ims.settings.domain.CompanyProfile;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Append-only (ADR-140): versions are added, never changed or removed. */
public interface CompanyProfileRepository extends Repository<CompanyProfile, Long> {

    CompanyProfile saveAndFlush(CompanyProfile profile);

    /** The current profile: the latest version. */
    Optional<CompanyProfile> findFirstByOrderByEffectiveFromDescIdDesc();

    /** The version in force at a moment: the latest that started at or before it. */
    Optional<CompanyProfile> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(Instant moment);

    /** For a document older than every version: the first one. */
    Optional<CompanyProfile> findFirstByOrderByEffectiveFromAscIdAsc();
}
