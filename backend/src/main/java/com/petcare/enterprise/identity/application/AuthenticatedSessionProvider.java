package com.petcare.enterprise.identity.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Supplies the server-verified session without exposing Spring Security to application or presentation code. */
public interface AuthenticatedSessionProvider {

    AuthenticatedSession requireSession();

    record AuthenticatedSession(
            UUID userId,
            UUID tenantId,
            UUID branchId,
            Set<String> authorities,
            UserProfile profile) {

        public AuthenticatedSession(
                UUID userId,
                UUID tenantId,
                UUID branchId,
                Set<String> authorities) {
            this(userId, tenantId, branchId, authorities, null);
        }
    }

    record UserProfile(
            String displayName,
            String email,
            List<String> roles,
            String clinicName,
            String branchName) {
    }
}
