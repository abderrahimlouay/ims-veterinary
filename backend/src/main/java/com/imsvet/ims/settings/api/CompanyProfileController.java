package com.imsvet.ims.settings.api;

import com.imsvet.ims.settings.application.CompanyProfileView;
import com.imsvet.ims.settings.application.CompanyProfiles;
import com.imsvet.ims.settings.application.CompanyProfiles.ProfileInput;
import com.imsvet.ims.settings.domain.CompanyProfile.Fields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** api.md §4.14: the company profile (ADR-140); a new version is posted as {@code multipart/form-data}. */
@RestController
@RequestMapping("/api/company-profile")
class CompanyProfileController {

    record ProfileRequest(@NotBlank @Size(max = 150) String legalName, @Size(max = 60) String legalForm,
            @Size(max = 2000) String address, @Size(max = 60) String wilaya, @Size(max = 40) String phone,
            @Email @Size(max = 150) String email, @Size(max = 40) String nif, @Size(max = 40) String nis,
            @Size(max = 40) String rc, @Size(max = 40) String ai, @Size(max = 60) String approvalNumber,
            @Size(max = 60) String bankAccount, @Size(max = 500) String footerText, MultipartFile logo,
            Boolean keepLogo) {

        ProfileInput input() {
            try {
                byte[] bytes = logo == null || logo.isEmpty() ? null : logo.getBytes();
                return new ProfileInput(new Fields(legalName, legalForm, address, wilaya, phone, email, nif, nis, rc,
                        ai, approvalNumber, bankAccount, footerText), bytes, Boolean.TRUE.equals(keepLogo));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private final CompanyProfiles profiles;

    CompanyProfileController(CompanyProfiles profiles) {
        this.profiles = profiles;
    }

    /** 204 while no version exists. */
    @GetMapping
    ResponseEntity<CompanyProfileView> current() {
        return profiles.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<CompanyProfileView> record(@Valid @ModelAttribute ProfileRequest request) {
        CompanyProfileView saved = profiles.record(request.input());
        return ResponseEntity.created(URI.create("/api/company-profile")).body(saved);
    }

    @GetMapping("/logo")
    ResponseEntity<byte[]> logo() {
        return profiles.currentLogo()
                .map(l -> ResponseEntity.ok().contentType(MediaType.parseMediaType(l.mediaType()))
                        .cacheControl(CacheControl.noCache()).body(l.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
