package com.petcare.enterprise.identity.infrastructure.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Immutable server-created identity contract. JWT authentication will construct it in HU-005. */
public record PetCarePrincipal(
        UUID userId,
        UUID tenantId,
        UUID branchId,
        List<GrantedAuthority> authorities) implements UserDetails {

    public PetCarePrincipal {
        Objects.requireNonNull(userId, "User id is required");
        authorities = List.copyOf(Objects.requireNonNull(authorities, "Authorities are required"));
        if (branchId != null && tenantId == null) {
            throw new IllegalArgumentException("A branch requires a tenant");
        }
    }

    public PetCarePrincipal(
            UUID userId,
            UUID tenantId,
            UUID branchId,
            Collection<? extends GrantedAuthority> authorities) {
        this(userId, tenantId, branchId, List.copyOf(authorities));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return userId.toString();
    }
}
