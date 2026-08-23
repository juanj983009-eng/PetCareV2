package com.petcare.enterprise.identity.application;

import java.util.Set;
import java.util.UUID;

public record TokenSubject(UUID userId, UUID tenantId, UUID branchId, Set<String> authorities) {

    public TokenSubject {
        authorities = Set.copyOf(authorities);
    }
}
