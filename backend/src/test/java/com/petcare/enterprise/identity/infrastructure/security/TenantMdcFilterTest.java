package com.petcare.enterprise.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantMdcFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesTrustedContextDuringRequestAndAlwaysCleansIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        StubTenantContext context = new StubTenantContext(new AuthenticatedTenant(tenantId, userId, branchId));
        TenantMdcFilter filter = new TenantMdcFilter(context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationIds.HEADER, correlationId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                duringRequest.set(MDC.getCopyOfContextMap()));

        assertThat(duringRequest.get()).containsEntry(TenantMdcFilter.CORRELATION_ID, correlationId.toString())
                .containsEntry(TenantMdcFilter.TENANT_ID, tenantId.toString())
                .containsEntry(TenantMdcFilter.USER_ID, userId.toString())
                .containsEntry(TenantMdcFilter.BRANCH_ID, branchId.toString());
        assertThat(response.getHeader(RequestCorrelationIds.HEADER)).isEqualTo(correlationId.toString());
        assertMdcIsClean();
    }

    @Test
    void sequentialRequestsAndAnonymousRequestDoNotShareTenantData() throws Exception {
        StubTenantContext context = new StubTenantContext(null);
        TenantMdcFilter filter = new TenantMdcFilter(context);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        AtomicReference<String> observedTenant = new AtomicReference<>();

        context.current = new AuthenticatedTenant(tenantA, UUID.randomUUID(), null);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> observedTenant.set(MDC.get(TenantMdcFilter.TENANT_ID)));
        assertThat(observedTenant.get()).isEqualTo(tenantA.toString());

        context.current = new AuthenticatedTenant(tenantB, UUID.randomUUID(), null);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> observedTenant.set(MDC.get(TenantMdcFilter.TENANT_ID)));
        assertThat(observedTenant.get()).isEqualTo(tenantB.toString());

        context.current = null;
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> observedTenant.set(MDC.get(TenantMdcFilter.TENANT_ID)));
        assertThat(observedTenant.get()).isNull();
        assertMdcIsClean();
    }

    @Test
    void exceptionDuringChainStillCleansMdc() {
        StubTenantContext context = new StubTenantContext(
                new AuthenticatedTenant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        TenantMdcFilter filter = new TenantMdcFilter(context);

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (request, response) -> {
                    throw new IllegalStateException("simulated failure");
                })).isInstanceOf(IllegalStateException.class);

        assertMdcIsClean();
    }

    @Test
    void trustedPlatformIdentityAddsUserWithoutInventingTenant() throws Exception {
        UUID platformUserId = UUID.randomUUID();
        TenantContext context = new StubTenantContext(null) {
            @Override
            public Optional<com.petcare.enterprise.tenancy.application.AuthenticatedIdentity> currentIdentity() {
                return Optional.of(new com.petcare.enterprise.tenancy.application.AuthenticatedIdentity(
                        platformUserId, null, null));
            }
        };
        TenantMdcFilter filter = new TenantMdcFilter(context);
        AtomicReference<Map<String, String>> duringRequest = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> duringRequest.set(MDC.getCopyOfContextMap()));

        assertThat(duringRequest.get()).containsEntry(TenantMdcFilter.USER_ID, platformUserId.toString())
                .doesNotContainKeys(TenantMdcFilter.TENANT_ID, TenantMdcFilter.BRANCH_ID);
        assertMdcIsClean();
    }

    private static void assertMdcIsClean() {
        assertThat(MDC.get(TenantMdcFilter.CORRELATION_ID)).isNull();
        assertThat(MDC.get(TenantMdcFilter.TENANT_ID)).isNull();
        assertThat(MDC.get(TenantMdcFilter.USER_ID)).isNull();
        assertThat(MDC.get(TenantMdcFilter.BRANCH_ID)).isNull();
    }

    private static class StubTenantContext implements TenantContext {
        private AuthenticatedTenant current;

        private StubTenantContext(AuthenticatedTenant current) {
            this.current = current;
        }

        @Override
        public Optional<AuthenticatedTenant> currentTenant() {
            return Optional.ofNullable(current);
        }

        @Override
        public AuthenticatedTenant requireTenant() {
            return current;
        }
    }
}
