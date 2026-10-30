package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.domain.EmailAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface IdentityRepository {

    Optional<LoginIdentity> findForLogin(UUID tenantId, EmailAddress email);

    Set<String> findEffectiveAuthorities(UUID tenantId, UUID userId);

    void updateLastAccess(UUID tenantId, UUID userId, Instant lastAccessAt);

    BootstrapIdentityResult ensureBootstrapIdentity(BootstrapIdentity command);

    default Optional<UserProfileData> findUserProfile(UUID tenantId, UUID userId) {
        return Optional.empty();
    }

    record UserProfileData(
            String firstName,
            String lastName,
            String email,
            List<String> roles) {
    }
}
