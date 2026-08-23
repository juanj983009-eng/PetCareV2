package com.petcare.enterprise.tenancy.domain;

public class DomainValidationException extends IllegalArgumentException {

    public DomainValidationException(String message) {
        super(message);
    }
}
