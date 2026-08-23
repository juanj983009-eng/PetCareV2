package com.petcare.enterprise.identity.domain;

public enum UserStatus {
    ACTIVO,
    BLOQUEADO,
    INACTIVO;

    public boolean canAuthenticate() {
        return this == ACTIVO;
    }
}
