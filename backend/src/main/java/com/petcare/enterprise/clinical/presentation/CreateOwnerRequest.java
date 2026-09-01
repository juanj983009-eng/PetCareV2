package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.NewOwner;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreateOwnerRequest(
        @NotBlank @Size(max=100) String firstName,
        @NotBlank @Size(max=100) String lastName,
        @Size(max=20) String documentType,
        @Size(max=30) String documentNumber,
        @NotBlank @Size(max=30) String phone,
        @Email @Size(max=180) String email,
        @Size(max=250) String address,
        @NotNull Boolean notificationsAccepted,
        boolean confirmPhoneDuplicate) {
    NewOwner toCommand() { return new NewOwner(firstName, lastName, documentType, documentNumber, phone, email, address, notificationsAccepted); }
}
