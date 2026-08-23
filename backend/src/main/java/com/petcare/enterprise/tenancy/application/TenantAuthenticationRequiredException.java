package com.petcare.enterprise.tenancy.application;

public final class TenantAuthenticationRequiredException extends RuntimeException {

    public TenantAuthenticationRequiredException() {
        super("Authentication is required");
    }
}
