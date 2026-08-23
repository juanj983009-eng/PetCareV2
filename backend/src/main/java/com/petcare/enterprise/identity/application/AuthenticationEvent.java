package com.petcare.enterprise.identity.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthenticationEvent(
        UUID id,
        UUID tenantId,
        UUID userId,
        Type type,
        Result result,
        Reason reason,
        Instant occurredAt,
        UUID correlationId) {

    public AuthenticationEvent {
        Objects.requireNonNull(id, "Event id is required");
        Objects.requireNonNull(type, "Event type is required");
        Objects.requireNonNull(result, "Event result is required");
        Objects.requireNonNull(reason, "Event reason is required");
        Objects.requireNonNull(occurredAt, "Event time is required");
        Objects.requireNonNull(correlationId, "Correlation id is required");
        if (userId != null && tenantId == null) {
            throw new IllegalArgumentException("An authentication user requires a tenant");
        }
    }

    public enum Type {
        LOGIN
    }

    public enum Result {
        SUCCESS,
        FAILURE
    }

    public enum Reason {
        AUTHENTICATED,
        TENANT_NOT_FOUND,
        TENANT_INACTIVE,
        USER_NOT_FOUND,
        USER_INACTIVE,
        USER_BLOCKED,
        BAD_PASSWORD,
        BRANCH_INVALID
    }
}
