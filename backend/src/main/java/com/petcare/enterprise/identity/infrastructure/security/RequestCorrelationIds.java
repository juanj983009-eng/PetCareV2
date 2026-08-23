package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.shared.infrastructure.web.CorrelationIdHeader;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

final class RequestCorrelationIds {

    static final String HEADER = CorrelationIdHeader.NAME;
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
            String candidate = value.trim();
            try {
                if (candidate.length() == 36) {
                    UUID parsed = UUID.fromString(candidate);
                    if (parsed.toString().equalsIgnoreCase(candidate)) {
                        return parsed;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed external values are replaced with a server-generated identifier.
            }
        }
        return UUID.randomUUID();
    }
}
