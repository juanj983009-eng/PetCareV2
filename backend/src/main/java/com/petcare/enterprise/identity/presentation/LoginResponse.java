package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.LoginResult;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Identity identity) {

    static LoginResponse from(LoginResult result) {
        return new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresAt(),
                new Identity(
                        result.userId(),
                        result.tenantId(),
                        result.branchId(),
                        result.authorities()));
    }

    record Identity(UUID userId, UUID tenantId, UUID branchId, Set<String> authorities) {
    }
}
