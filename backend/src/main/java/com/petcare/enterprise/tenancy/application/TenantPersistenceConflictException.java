package com.petcare.enterprise.tenancy.application;

public class TenantPersistenceConflictException extends RuntimeException {

    public TenantPersistenceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
