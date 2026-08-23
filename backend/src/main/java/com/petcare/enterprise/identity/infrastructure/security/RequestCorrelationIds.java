package com.petcare.enterprise.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class RequestCorrelationIds {

    static final String HEADER = "X-Correlation-ID";
    static final String ATTRIBUTE = RequestCorrelationIds.class.getName() + ".correlationId";

    private RequestCorrelationIds() {
    }

    static UUID getOrCreate(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID correlationId) {
            return correlationId;
        }
        UUID correlationId = parseOrCreate(request.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        return correlationId;
    }

    private static UUID parseOrCreate(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return UUID.fromString(value.trim());
            } catch (IllegalArgumentException ignored) {
                // Malformed external values are replaced with a server-generated identifier.
            }
        }
        return UUID.randomUUID();
    }
}
