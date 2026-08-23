package com.petcare.enterprise.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petcare.enterprise.tenancy.application.TenantAccessDeniedException;
import com.petcare.enterprise.tenancy.application.TenantAuthenticationRequiredException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SpringSecurityTenantContextTest {

    private final SpringSecurityTenantContext tenantContext = new SpringSecurityTenantContext();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesTrustedTenantUserAndBranch() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        authenticate(principal(userId, tenantId, branchId));

        var current = tenantContext.requireTenant();

        assertThat(current.tenantId()).isEqualTo(tenantId);
        assertThat(current.userId()).isEqualTo(userId);
        assertThat(current.branchId()).isEqualTo(branchId);
    }

    @Test
    void trustedTenantWorksWithoutBranch() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        authenticate(principal(userId, tenantId, null));

        assertThat(tenantContext.requireTenant().optionalBranchId()).isEmpty();
    }

    @Test
    void anonymousRequestHasNoTenantAndRequiresAuthentication() {
        assertThat(tenantContext.currentTenant()).isEmpty();
        assertThatThrownBy(tenantContext::requireTenant)
                .isInstanceOf(TenantAuthenticationRequiredException.class);
    }

    @Test
    void genericSpringPrincipalIsNotTrustedAsTenantContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.test", "n/a", List.of()));

        assertThat(tenantContext.currentTenant()).isEmpty();
        assertThatThrownBy(tenantContext::requireTenant)
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void authenticatedPlatformIdentityWithoutTenantIsRejectedForTenantOperation() {
        UUID platformUserId = UUID.randomUUID();
        authenticate(principal(platformUserId, null, null));

        assertThat(tenantContext.currentIdentity()).get().extracting("userId").isEqualTo(platformUserId);
        assertThat(tenantContext.currentTenant()).isEmpty();
        assertThatThrownBy(tenantContext::requireTenant)
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void malformedOrNullIdentityValuesCannotCreateTenantContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("not-a-uuid", "n/a", List.of()));
        assertThat(tenantContext.currentTenant()).isEmpty();

        assertThatThrownBy(() -> principal(null, UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> principal(UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PetCarePrincipal principal(UUID userId, UUID tenantId, UUID branchId) {
        return new PetCarePrincipal(
                userId,
                tenantId,
                branchId,
                List.of(new SimpleGrantedAuthority("TENANT_TEST")));
    }

    private static void authenticate(PetCarePrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }
}
