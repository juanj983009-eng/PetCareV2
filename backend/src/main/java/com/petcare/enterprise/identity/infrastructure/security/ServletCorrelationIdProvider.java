package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
final class ServletCorrelationIdProvider implements CorrelationIdProvider {

    @Override
    public UUID currentCorrelationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return RequestCorrelationIds.getOrCreate(request);
        }
        return UUID.randomUUID();
    }
}
