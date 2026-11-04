package com.imsvet.ims.settings.infrastructure;

import com.imsvet.ims.settings.domain.CompanyProfile;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Append-only (ADR-140): versions are added, never changed or removed. */
public interface CompanyProfileRepository extends Repository<CompanyProfile, Long> {

    CompanyProfile saveAndFlush(CompanyProfile profile);

    /** The current profile: the latest version. */
    Optional<CompanyProfile> findFirstByOrderByEffectiveFromDescIdDesc();
}
