package com.petcare.enterprise.identity.presentation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

record CreateUserRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Size(min = 12, max = 200) String initialPassword,
        @NotNull UUID roleId,
        @NotNull UUID branchId) {
}
