package com.imsvet.ims.settings.application;

import com.imsvet.ims.common.security.InternalService;
import com.imsvet.ims.settings.domain.CompanyProfile.Fields;
import com.imsvet.ims.settings.infrastructure.CompanyProfileRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company identity a document prints (ADR-140), for the {@code printing} module: the version in force at the
 * document's issue, the first version for a document older than all of them, nothing while none exists. Callers
 * check the reading permission of the document they print.
 */
@Service
@InternalService
public class CompanyIdentity {

    /** The identity fields and the logo with its media type (both {@code null} without a logo). */
    public record Identity(Fields fields, byte[] logo, String logoType) {
    }

    private final CompanyProfileRepository profiles;

    public CompanyIdentity(CompanyProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public Optional<Identity> at(Instant moment) {
        return profiles.findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(moment)
                .or(profiles::findFirstByOrderByEffectiveFromAscIdAsc)
                .map(p -> new Identity(p.fields(), p.logo(), p.logoType()));
    }

    /** For documents printed as of now: a statement, a cash-session report, a recall list. */
    @Transactional(readOnly = true)
    public Optional<Identity> current() {
        return profiles.findFirstByOrderByEffectiveFromDescIdDesc().map(p -> new Identity(p.fields(), p.logo(),
                p.logoType()));
    }
}
