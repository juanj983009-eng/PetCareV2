package com.petcare.enterprise.identity.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record LoginResult(
        String accessToken,
        Instant expiresAt,
        UUID userId,
        UUID tenantId,
        UUID branchId,
        Set<String> authorities) {

    public LoginResult {
        authorities = Set.copyOf(authorities);
    }
}
