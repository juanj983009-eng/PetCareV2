package com.petcare.enterprise.tenancy.application;

import java.util.UUID;

/** Tenant-local provisioning boundary for the mandatory RBAC system roles. */
public interface SystemRoleProvisioner {

    void ensureForTenant(UUID tenantId);
}
