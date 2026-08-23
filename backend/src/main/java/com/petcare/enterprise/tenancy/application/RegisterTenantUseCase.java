package com.petcare.enterprise.tenancy.application;

public interface RegisterTenantUseCase {

    RegisteredTenant register(RegisterTenantCommand command);
}
