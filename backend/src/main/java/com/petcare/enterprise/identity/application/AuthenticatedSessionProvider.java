package com.petcare.enterprise.identity.application;

import java.util.Set;
import java.util.UUID;

/** Supplies the server-verified session without exposing Spring Security to application or presentation code. */
public interface AuthenticatedSessionProvider {

    AuthenticatedSession requireSession();

    record AuthenticatedSession(
            UUID userId,
            UUID tenantId,
            UUID branchId,
            Set<String> authorities) {
    }
}
