package com.petcare.enterprise.clinical.application;

import java.util.UUID;

public interface OwnerRegistrationUseCase {
    Owner register(NewOwner owner, boolean confirmPhoneDuplicate);
    record NewOwner(String firstName, String lastName, String documentType, String documentNumber, String phone, String email, String address, boolean notificationsAccepted) { }
    record Owner(UUID id, String firstName, String lastName, String documentType, String documentNumber, String phone, String email, String address, boolean notificationsAccepted, boolean active) { }
}
