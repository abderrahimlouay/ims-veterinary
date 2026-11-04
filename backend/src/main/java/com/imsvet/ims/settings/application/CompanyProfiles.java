package com.imsvet.ims.settings.application;

import com.imsvet.ims.audit.application.AuditRecorder;
import com.imsvet.ims.audit.domain.AuditAction;
import com.imsvet.ims.common.actor.ActorProvider;
import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.settings.domain.CompanyProfile;
import com.imsvet.ims.settings.domain.CompanyProfile.Fields;
import com.imsvet.ims.settings.infrastructure.CompanyProfileRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company profile (ADR-140): every user reads the current one; {@code settings.manage} adds a version, never an
 * edit, audited {@code SETTING_CHANGE} in the same transaction without the logo's bytes (BR-AUDIT-001/003).
 */
@Service
public class CompanyProfiles {

    /** ADR-140: PNG or JPEG, at most 256 KB, recognised by its first bytes rather than the declared type. */
    public static final int MAX_LOGO_BYTES = 256 * 1024;

    /** The new version's fields; {@code logo} replaces the current logo, else {@code keepLogo} keeps it. */
    public record ProfileInput(Fields fields, byte[] logo, boolean keepLogo) {
    }

    /** A logo and its media type. */
    public record Logo(byte[] bytes, String mediaType) {
    }

    private final CompanyProfileRepository profiles;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public CompanyProfiles(CompanyProfileRepository profiles, ActorProvider actors, AuditRecorder audit, Clock clock) {
        this.profiles = profiles;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Optional<CompanyProfileView> current() {
        return profiles.findFirstByOrderByEffectiveFromDescIdDesc().map(CompanyProfiles::view);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Optional<Logo> currentLogo() {
        return profiles.findFirstByOrderByEffectiveFromDescIdDesc().filter(CompanyProfile::hasLogo)
                .map(p -> new Logo(p.logo(), p.logoType()));
    }

    @PreAuthorize("hasAuthority('settings.manage')")
    @Transactional
    public CompanyProfileView record(ProfileInput in) {
        Fields fields = trimmed(in.fields());
        List<Violation> violations = new ArrayList<>();
        if (fields.legalName() == null) {
            violations.add(new Violation("legalName", "REQUIRED", "The legal name is required."));
        }
        String logoType = null;
        if (in.logo() != null && in.logo().length > 0) {
            logoType = mediaType(in.logo());
            if (in.logo().length > MAX_LOGO_BYTES) {
                violations.add(new Violation("logo", "SIZE", "The logo is at most 256 KB."));
            } else if (logoType == null) {
                violations.add(new Violation("logo", "UNKNOWN", "The logo is a PNG or JPEG image."));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("The company profile is invalid.", violations);
        }
        Optional<CompanyProfile> previous = profiles.findFirstByOrderByEffectiveFromDescIdDesc();
        byte[] logo = logoType != null ? in.logo() : null;
        if (logo == null && in.keepLogo()) {
            logo = previous.filter(CompanyProfile::hasLogo).map(CompanyProfile::logo).orElse(null);
            logoType = previous.filter(CompanyProfile::hasLogo).map(CompanyProfile::logoType).orElse(null);
        }
        CompanyProfile saved = profiles.saveAndFlush(new CompanyProfile(fields, logo, logoType,
                actors.current().userId(), clock.instant()));
        audit.record(AuditAction.SETTING_CHANGE, "company_profiles", saved.id(),
                previous.map(CompanyProfiles::auditValues).orElse(null), auditValues(saved));
        return view(saved);
    }

    private static Fields trimmed(Fields f) {
        return new Fields(blankToNull(f.legalName()), blankToNull(f.legalForm()), blankToNull(f.address()),
                blankToNull(f.wilaya()), blankToNull(f.phone()), blankToNull(f.email()), blankToNull(f.nif()),
                blankToNull(f.nis()), blankToNull(f.rc()), blankToNull(f.ai()), blankToNull(f.approvalNumber()),
                blankToNull(f.bankAccount()), blankToNull(f.footerText()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** PNG (89 50 4E 47) or JPEG (FF D8 FF) by signature; anything else is refused. */
    static String mediaType(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    private static Map<String, Object> auditValues(CompanyProfile p) {
        Fields f = p.fields();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("legalName", f.legalName());
        values.put("legalForm", f.legalForm());
        values.put("address", f.address());
        values.put("wilaya", f.wilaya());
        values.put("phone", f.phone());
        values.put("email", f.email());
        values.put("nif", f.nif());
        values.put("nis", f.nis());
        values.put("rc", f.rc());
        values.put("ai", f.ai());
        values.put("approvalNumber", f.approvalNumber());
        values.put("bankAccount", f.bankAccount());
        values.put("footerText", f.footerText());
        values.put("logo", p.hasLogo() ? p.logoType() : null);
        return values;
    }

    private static CompanyProfileView view(CompanyProfile p) {
        Fields f = p.fields();
        return new CompanyProfileView(p.id(), f.legalName(), f.legalForm(), f.address(), f.wilaya(), f.phone(),
                f.email(), f.nif(), f.nis(), f.rc(), f.ai(), f.approvalNumber(), f.bankAccount(), f.footerText(),
                p.hasLogo(), p.effectiveFrom(), p.createdBy());
    }
}
