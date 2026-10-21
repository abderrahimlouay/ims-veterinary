package com.imsvet.ims.users.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * One of the four seeded roles (FR-SEC-002) and its permissions (ADR-037).
 */
@Entity
@Table(name = "roles")
public class Role {

    public static final String ADMINISTRATOR = "Administrator";

    @Id
    private Short id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToMany
    @JoinTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
    }

    public Short id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** Replaces the whole permission set (ADR-037: codes come from the seeded catalogue). */
    public void replacePermissions(Set<Permission> newPermissions) {
        permissions.clear();
        permissions.addAll(newPermissions);
    }

    public String name() {
        return name;
    }

    public Set<Permission> permissions() {
        return Set.copyOf(permissions);
    }
}
