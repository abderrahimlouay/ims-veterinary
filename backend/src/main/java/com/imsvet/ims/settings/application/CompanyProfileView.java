package com.imsvet.ims.settings.application;

import java.time.Instant;

/** api.md §4.14: a version of the company profile; the logo is served apart ({@code hasLogo}). */
public record CompanyProfileView(long id, String legalName, String legalForm, String address, String wilaya,
        String phone, String email, String nif, String nis, String rc, String ai, String approvalNumber,
        String bankAccount, String footerText, boolean hasLogo, Instant effectiveFrom, long createdBy) {
}
