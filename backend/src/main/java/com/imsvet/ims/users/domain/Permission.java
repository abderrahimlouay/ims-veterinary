package com.imsvet.ims.users.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A permission code of the seeded catalogue (ADR-037); never created at runtime.
 */
@Entity
@Immutable
@Table(name = "permissions")
public class Permission {

    @Id
    private Integer id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(columnDefinition = "text")
    private String description;

    protected Permission() {
    }

    public String code() {
        return code;
    }
}
