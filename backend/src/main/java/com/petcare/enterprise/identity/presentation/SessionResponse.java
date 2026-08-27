package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.AuthenticatedSessionProvider.AuthenticatedSession;
import java.util.Set;
import java.util.UUID;

record SessionResponse(UUID userId, UUID tenantId, UUID branchId, Set<String> authorities) {

    static SessionResponse from(AuthenticatedSession session) {
        return new SessionResponse(
                session.userId(),
                session.tenantId(),
                session.branchId(),
                session.authorities());
    }
}
