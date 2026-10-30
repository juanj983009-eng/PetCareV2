package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.AuthenticatedSessionProvider.AuthenticatedSession;
import com.petcare.enterprise.identity.application.AuthenticatedSessionProvider.UserProfile;
import java.util.List;
import java.util.Set;
import java.util.UUID;

record SessionResponse(
        UUID userId,
        UUID tenantId,
        UUID branchId,
        Set<String> authorities,
        ProfileResponse profile) {

    static SessionResponse from(AuthenticatedSession session) {
        return new SessionResponse(
                session.userId(),
                session.tenantId(),
                session.branchId(),
                session.authorities(),
                ProfileResponse.from(session.profile()));
    }

    record ProfileResponse(
            String displayName,
            String email,
            List<String> roles,
            String clinicName,
            String branchName) {

        static ProfileResponse from(UserProfile profile) {
            return profile == null ? null : new ProfileResponse(
                    profile.displayName(),
                    profile.email(),
                    profile.roles(),
                    profile.clinicName(),
                    profile.branchName());
        }
    }
}
