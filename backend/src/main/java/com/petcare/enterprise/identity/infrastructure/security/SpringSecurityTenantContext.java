package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.tenancy.application.AuthenticatedIdentity;
import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.TenantAccessDeniedException;
import com.petcare.enterprise.tenancy.application.TenantAuthenticationRequiredException;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
final class SpringSecurityTenantContext implements TenantContext {

    @Override
    public Optional<AuthenticatedIdentity> currentIdentity() {
        Authentication authentication = currentAuthentication();
        if (!isAuthenticated(authentication) || !(authentication.getPrincipal() instanceof PetCarePrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedIdentity(
                principal.userId(),
                principal.tenantId(),
                principal.branchId()));
    }

    @Override
    public Optional<AuthenticatedTenant> currentTenant() {
        return currentIdentity()
                .filter(identity -> identity.tenantId() != null)
                .map(identity -> new AuthenticatedTenant(
                        identity.tenantId(), identity.userId(), identity.branchId()));
    }

    @Override
    public AuthenticatedTenant requireTenant() {
        Authentication authentication = currentAuthentication();
        if (!isAuthenticated(authentication)) {
            throw new TenantAuthenticationRequiredException();
        }
        if (!(authentication.getPrincipal() instanceof PetCarePrincipal principal)
                || principal.tenantId() == null) {
            throw new TenantAccessDeniedException();
        }
        return new AuthenticatedTenant(principal.tenantId(), principal.userId(), principal.branchId());
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
