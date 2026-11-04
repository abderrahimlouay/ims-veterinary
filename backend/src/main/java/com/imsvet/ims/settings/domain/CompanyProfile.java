package com.imsvet.ims.settings.domain;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One version of the pharmacy's identity printed on documents (ADR-140). Append-only: a change is a new version,
 * never an edit; a document prints the latest version in force at its issue time.
 */
@Entity
@Table(name = "company_profiles")
public class CompanyProfile {

    /** The identity fields, already validated; {@code logo} with its media type, or both {@code null}. */
    public record Fields(String legalName, String legalForm, String address, String wilaya, String phone,
            String email, String nif, String nis, String rc, String ai, String approvalNumber, String bankAccount,
            String footerText) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "legal_name", nullable = false, updatable = false, length = 150)
    private String legalName;

    @Column(name = "legal_form", updatable = false, length = 60)
    private String legalForm;

    @Column(updatable = false, columnDefinition = "TEXT")
    private String address;

    @Column(updatable = false, length = 60)
    private String wilaya;

    @Column(updatable = false, length = 40)
    private String phone;

    @Column(updatable = false, length = 150)
    private String email;

    @Column(updatable = false, length = 40)
    private String nif;

    @Column(updatable = false, length = 40)
    private String nis;

    @Column(updatable = false, length = 40)
    private String rc;

    @Column(updatable = false, length = 40)
    private String ai;

    @Column(name = "approval_number", updatable = false, length = 60)
    private String approvalNumber;

    @Column(name = "bank_account", updatable = false, length = 60)
    private String bankAccount;

    @Column(name = "footer_text", updatable = false, length = 500)
    private String footerText;

    @Basic(fetch = FetchType.LAZY)
    @Column(updatable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] logo;

    @Column(name = "logo_type", updatable = false, length = 20)
    private String logoType;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    protected CompanyProfile() {
    }

    public CompanyProfile(Fields f, byte[] logo, String logoType, Long createdBy, Instant now) {
        this.legalName = f.legalName();
        this.legalForm = f.legalForm();
        this.address = f.address();
        this.wilaya = f.wilaya();
        this.phone = f.phone();
        this.email = f.email();
        this.nif = f.nif();
        this.nis = f.nis();
        this.rc = f.rc();
        this.ai = f.ai();
        this.approvalNumber = f.approvalNumber();
        this.bankAccount = f.bankAccount();
        this.footerText = f.footerText();
        this.logo = logo;
        this.logoType = logoType;
        this.createdBy = createdBy;
        this.effectiveFrom = now;
    }

    public Long id() {
        return id;
    }

    public Fields fields() {
        return new Fields(legalName, legalForm, address, wilaya, phone, email, nif, nis, rc, ai, approvalNumber,
                bankAccount, footerText);
    }

    public byte[] logo() {
        return logo;
    }

    public String logoType() {
        return logoType;
    }

    public boolean hasLogo() {
        return logoType != null;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Long createdBy() {
        return createdBy;
    }
}
