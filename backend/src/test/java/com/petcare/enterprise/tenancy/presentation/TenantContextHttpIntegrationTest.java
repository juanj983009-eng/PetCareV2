package com.petcare.enterprise.tenancy.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.TenantContext;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TenantContextHttpIntegrationTest.ProbeConfiguration.class)
class TenantContextHttpIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String ENDPOINT = "/test-only/tenant-context/{resourceTenantId}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void hostileHeadersQueryPathAndBodyCannotReplaceAuthenticatedTenant() throws Exception {
        UUID trustedTenant = UUID.randomUUID();
        UUID trustedUser = UUID.randomUUID();
        UUID trustedBranch = UUID.randomUUID();
        UUID attackerTenant = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        mockMvc.perform(post(ENDPOINT, attackerTenant)
                        .with(authentication(trustedAuthentication(trustedUser, trustedTenant, trustedBranch)))
                        .header("X-Tenant-Id", attackerTenant)
                        .header("X-Clinic-Id", attackerTenant)
                        .header("X-Correlation-ID", correlationId)
                        .queryParam("tenantId", attackerTenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + attackerTenant + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(header().doesNotExist("X-Tenant-Id"))
                .andExpect(header().doesNotExist("X-User-Id"))
                .andExpect(jsonPath("$.tenantId").value(trustedTenant.toString()))
                .andExpect(jsonPath("$.userId").value(trustedUser.toString()))
                .andExpect(jsonPath("$.branchId").value(trustedBranch.toString()))
                .andExpect(jsonPath("$.mdcTenantId").value(trustedTenant.toString()))
                .andExpect(jsonPath("$.mdcUserId").value(trustedUser.toString()))
                .andExpect(jsonPath("$.mdcBranchId").value(trustedBranch.toString()))
                .andExpect(jsonPath("$.mdcCorrelationId").value(correlationId.toString()))
                .andExpect(jsonPath("$.auditTenantId").value(trustedTenant.toString()))
                .andExpect(jsonPath("$.auditUserId").value(trustedUser.toString()))
                .andExpect(jsonPath("$.auditBranchId").value(trustedBranch.toString()))
                .andExpect(jsonPath("$.auditCorrelationId").value(correlationId.toString()));

        assertMdcIsClean();
    }

    @Test
    void sequentialRequestsWithDifferentTenantsDoNotShareContext() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        mockMvc.perform(post(ENDPOINT, UUID.randomUUID())
                        .with(authentication(trustedAuthentication(UUID.randomUUID(), tenantA, null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.branchId").doesNotExist());

        mockMvc.perform(post(ENDPOINT, UUID.randomUUID())
                        .with(authentication(trustedAuthentication(UUID.randomUUID(), tenantB, null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantB.toString()))
                .andExpect(jsonPath("$.branchId").doesNotExist());

        assertMdcIsClean();
    }

    @Test
    void anonymousRequestReceivesUnauthorized() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void genericAuthenticatedPrincipalReceivesForbidden() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID())
                        .with(user("generic-user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void platformPrincipalWithoutTenantReceivesForbiddenForTenantOperation() throws Exception {
        mockMvc.perform(post(ENDPOINT, UUID.randomUUID())
                        .with(authentication(trustedAuthentication(UUID.randomUUID(), null, null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private static UsernamePasswordAuthenticationToken trustedAuthentication(
            UUID userId, UUID tenantId, UUID branchId) {
        PetCarePrincipal principal = new PetCarePrincipal(
                userId,
                tenantId,
                branchId,
                List.of(new SimpleGrantedAuthority("TENANT_TEST")));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());
    }

    private static void assertMdcIsClean() {
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("branchId")).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        TenantContextProbeController tenantContextProbeController(
                TenantContext tenantContext,
                AuditMetadataProvider auditMetadataProvider) {
            return new TenantContextProbeController(tenantContext, auditMetadataProvider);
        }
    }

    @RestController
    @RequestMapping("/test-only/tenant-context")
    static class TenantContextProbeController {

        private final TenantContext tenantContext;
        private final AuditMetadataProvider auditMetadataProvider;

        TenantContextProbeController(
                TenantContext tenantContext,
                AuditMetadataProvider auditMetadataProvider) {
            this.tenantContext = tenantContext;
            this.auditMetadataProvider = auditMetadataProvider;
        }

        @PostMapping("/{resourceTenantId}")
        Map<String, Object> current(
                @PathVariable UUID resourceTenantId,
                @RequestBody Map<String, Object> ignoredBody) {
            AuthenticatedTenant current = tenantContext.requireTenant();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tenantId", current.tenantId());
            response.put("userId", current.userId());
            if (current.branchId() != null) {
                response.put("branchId", current.branchId());
            }
            response.put("mdcTenantId", MDC.get("tenantId"));
            response.put("mdcUserId", MDC.get("userId"));
            if (MDC.get("branchId") != null) {
                response.put("mdcBranchId", MDC.get("branchId"));
            }
            response.put("mdcCorrelationId", MDC.get("correlationId"));
            var auditMetadata = auditMetadataProvider.requireTenantMetadata();
            response.put("auditTenantId", auditMetadata.tenantId());
            response.put("auditUserId", auditMetadata.actorUserId());
            if (auditMetadata.branchId() != null) {
                response.put("auditBranchId", auditMetadata.branchId());
            }
            response.put("auditCorrelationId", auditMetadata.correlationId());
            return response;
        }
    }
}
