package com.petcare.enterprise.tenancy.presentation;

import java.util.UUID;

final class CorrelationIds {

    private CorrelationIds() {
    }

    static UUID resolve(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value.trim());
            } catch (IllegalArgumentException ignored) {
                // A new safe correlation id is generated for malformed external input.
            }
        }
        return UUID.randomUUID();
    }
}
