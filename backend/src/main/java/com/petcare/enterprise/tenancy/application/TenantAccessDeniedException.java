package com.petcare.enterprise.tenancy.application;

public final class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException() {
        super("The authenticated identity has no valid tenant context");
    }
}
