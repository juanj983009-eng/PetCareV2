package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import java.util.UUID;

record SessionResponse(UUID userId, UUID tenantId, UUID branchId) {

    static SessionResponse from(AuthenticatedTenant tenant) {
        return new SessionResponse(tenant.userId(), tenant.tenantId(), tenant.branchId());
    }
}
