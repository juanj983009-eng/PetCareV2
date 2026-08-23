package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.TenantContext;
import org.springframework.stereotype.Component;

@Component
final class TrustedAuditMetadataProvider implements AuditMetadataProvider {

    private final TenantContext tenantContext;
    private final CorrelationIdProvider correlationIdProvider;

    TrustedAuditMetadataProvider(TenantContext tenantContext, CorrelationIdProvider correlationIdProvider) {
        this.tenantContext = tenantContext;
        this.correlationIdProvider = correlationIdProvider;
    }

    @Override
    public AuditMetadata requireTenantMetadata() {
        AuthenticatedTenant current = tenantContext.requireTenant();
        return new AuditMetadata(
                current.tenantId(),
                current.userId(),
                current.branchId(),
                correlationIdProvider.currentCorrelationId());
    }
}
