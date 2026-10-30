package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.identity.application.AuthenticatedSessionProvider;
import com.petcare.enterprise.identity.application.IdentityRepository;
import com.petcare.enterprise.tenancy.application.AuthenticatedIdentity;
import com.petcare.enterprise.tenancy.application.AuthenticatedTenant;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.TenantAccessDeniedException;
import com.petcare.enterprise.tenancy.application.TenantAuthenticationRequiredException;
import com.petcare.enterprise.tenancy.application.TenantContext;
import com.petcare.enterprise.tenancy.application.TenantRepository;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
final class SpringSecurityTenantContext implements TenantContext, AuthenticatedSessionProvider {

    private final IdentityRepository identityRepository;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;

    @Autowired
    SpringSecurityTenantContext(
            IdentityRepository identityRepository,
            TenantRepository tenantRepository,
            BranchRepository branchRepository) {
        this.identityRepository = identityRepository;
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
    }

    SpringSecurityTenantContext() {
        this(null, null, null);
    }

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

    @Override
    public AuthenticatedSession requireSession() {
        Authentication authentication = currentAuthentication();
        if (!isAuthenticated(authentication)) {
            throw new TenantAuthenticationRequiredException();
        }
        if (!(authentication.getPrincipal() instanceof PetCarePrincipal principal)
                || principal.tenantId() == null) {
            throw new TenantAccessDeniedException();
        }
        UserProfile profile = resolveProfile(principal.tenantId(), principal.userId(), principal.branchId());
        return new AuthenticatedSession(
                principal.userId(),
                principal.tenantId(),
                principal.branchId(),
                principal.authorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toUnmodifiableSet()),
                profile);
    }

    private UserProfile resolveProfile(UUID tenantId, UUID userId, UUID branchId) {
        if (identityRepository == null || tenantRepository == null) {
            return null;
        }
        var userRecord = identityRepository.findUserProfile(tenantId, userId).orElse(null);
        String displayName = userRecord != null
                ? (userRecord.firstName() + " " + userRecord.lastName()).trim()
                : "";
        String email = userRecord != null ? userRecord.email() : "";
        List<String> roles = userRecord != null ? userRecord.roles() : List.of();

        String clinicName = tenantRepository.findById(tenantId)
                .map(Tenant::name)
                .orElse("");

        String branchName = null;
        if (branchId != null && branchRepository != null) {
            branchName = branchRepository.findByTenantIdAndId(tenantId, branchId)
                    .map(Branch::name)
                    .orElse(null);
        }

        return new UserProfile(displayName, email, roles, clinicName, branchName);
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
