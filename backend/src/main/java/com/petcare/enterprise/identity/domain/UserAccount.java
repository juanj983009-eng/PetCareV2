package com.petcare.enterprise.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserAccount(
        UUID id,
        UUID tenantId,
        String firstName,
        String lastName,
        EmailAddress email,
        String passwordHash,
        UserStatus status,
        Instant lastAccessAt,
        Instant createdAt) {

    public UserAccount {
        Objects.requireNonNull(id, "User id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        firstName = required(firstName, 100, "First name");
        lastName = required(lastName, 100, "Last name");
        Objects.requireNonNull(email, "Email is required");
        passwordHash = required(passwordHash, 255, "Password hash");
        Objects.requireNonNull(status, "User status is required");
        Objects.requireNonNull(createdAt, "Creation time is required");
    }

    private static String required(String value, int maximumLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
