package com.petcare.enterprise.tenancy.application;

import java.util.Optional;

/**
 * Application port for server-authenticated tenant identity.
 *
 * <p>Global platform operations must not call this port. Tenant-bound use cases must call
 * {@link #requireTenant()} and use the returned tenant id for every business query.</p>
 */
public interface TenantContext {

    default Optional<AuthenticatedIdentity> currentIdentity() {
        return currentTenant().map(current -> new AuthenticatedIdentity(
                current.userId(), current.tenantId(), current.branchId()));
    }

    Optional<AuthenticatedTenant> currentTenant();

    AuthenticatedTenant requireTenant();
}
