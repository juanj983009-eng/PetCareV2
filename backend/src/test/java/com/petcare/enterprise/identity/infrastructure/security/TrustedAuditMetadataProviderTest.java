package com.petcare.enterprise.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustedAuditMetadataProviderTest {

    @Test
    void obtainsTenantActorBranchAndCorrelationFromTrustedPorts() {
        AuthenticatedTenant identity = new AuthenticatedTenant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID correlationId = UUID.randomUUID();
        TenantContext tenantContext = new TenantContext() {
            @Override
            public Optional<AuthenticatedTenant> currentTenant() {
                return Optional.of(identity);
            }

            @Override
            public AuthenticatedTenant requireTenant() {
                return identity;
            }
        };
        TrustedAuditMetadataProvider provider = new TrustedAuditMetadataProvider(
                tenantContext,
                () -> correlationId);

        var metadata = provider.requireTenantMetadata();

        assertThat(metadata.tenantId()).isEqualTo(identity.tenantId());
        assertThat(metadata.actorUserId()).isEqualTo(identity.userId());
        assertThat(metadata.branchId()).isEqualTo(identity.branchId());
        assertThat(metadata.correlationId()).isEqualTo(correlationId);
    }
}
