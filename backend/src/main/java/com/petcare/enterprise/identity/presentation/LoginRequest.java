package com.petcare.enterprise.identity.presentation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record LoginRequest(
        @NotBlank
        @Size(min = 3, max = 80)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
        String clinicSlug,
        @NotBlank
        @Email
        @Size(max = 180)
        String email,
        @NotBlank
        @Size(max = 200)
        String password) {
}
