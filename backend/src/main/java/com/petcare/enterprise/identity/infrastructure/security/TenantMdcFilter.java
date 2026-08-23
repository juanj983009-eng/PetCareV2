package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.tenancy.application.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

final class TenantMdcFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID = "correlationId";
    static final String TENANT_ID = "tenantId";
    static final String USER_ID = "userId";
    static final String BRANCH_ID = "branchId";

    private final TenantContext tenantContext;

    TenantMdcFilter(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            clearMdc();
            var correlationId = RequestCorrelationIds.getOrCreate(request);
            response.setHeader(RequestCorrelationIds.HEADER, correlationId.toString());
            MDC.put(CORRELATION_ID, correlationId.toString());
            tenantContext.currentIdentity().ifPresent(current -> {
                MDC.put(USER_ID, current.userId().toString());
                current.optionalTenantId().ifPresent(tenantId -> MDC.put(TENANT_ID, tenantId.toString()));
                current.optionalBranchId().ifPresent(branchId -> MDC.put(BRANCH_ID, branchId.toString()));
            });
            filterChain.doFilter(request, response);
        } finally {
            clearMdc();
        }
    }

    private static void clearMdc() {
        MDC.remove(BRANCH_ID);
        MDC.remove(USER_ID);
        MDC.remove(TENANT_ID);
        MDC.remove(CORRELATION_ID);
    }
}
