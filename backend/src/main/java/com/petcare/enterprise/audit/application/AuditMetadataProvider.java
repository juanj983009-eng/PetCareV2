package com.petcare.enterprise.audit.application;

/** Supplies trusted actor metadata for tenant-bound audit records. */
public interface AuditMetadataProvider {

    AuditMetadata requireTenantMetadata();
}
