package com.petcare.enterprise.tenancy.application;

public class TenantSlugAlreadyExistsException extends RuntimeException {

    public TenantSlugAlreadyExistsException() {
        super("Tenant slug already exists");
    }
}
