package com.petcare.enterprise.identity.application;

import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserAccount;
import com.petcare.enterprise.identity.domain.UserStatus;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.TenantRepository;
import com.petcare.enterprise.tenancy.domain.Tenant;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService implements LoginUseCase {

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final IdentityRepository identityRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AuthenticationEventWriter eventWriter;
    private final Clock clock;
    private final String dummyPasswordHash;

    public LoginService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            IdentityRepository identityRepository,
            PasswordHasher passwordHasher,
            AccessTokenIssuer accessTokenIssuer,
            AuthenticationEventWriter eventWriter,
            Clock clock) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.identityRepository = identityRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.eventWriter = eventWriter;
        this.clock = clock;
        this.dummyPasswordHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        Objects.requireNonNull(command, "Login command is required");
        Objects.requireNonNull(command.correlationId(), "Correlation id is required");
        EmailAddress email = new EmailAddress(command.email());
        String slug = normalizeSlug(command.clinicSlug());
        String password = Objects.requireNonNull(command.password(), "Password is required");

        Tenant tenant = tenantRepository.findBySlugForAuthentication(slug).orElse(null);
        if (tenant == null) {
            passwordHasher.matches(password, dummyPasswordHash);
            reject(null, null, AuthenticationEvent.Reason.TENANT_NOT_FOUND, command.correlationId());
        }
        if (tenant.status() != TenantStatus.ACTIVO) {
            passwordHasher.matches(password, dummyPasswordHash);
            reject(tenant.id(), null, AuthenticationEvent.Reason.TENANT_INACTIVE, command.correlationId());
        }

        LoginIdentity identity = identityRepository.findForLogin(tenant.id(), email).orElse(null);
        if (identity == null) {
            passwordHasher.matches(password, dummyPasswordHash);
            reject(tenant.id(), null, AuthenticationEvent.Reason.USER_NOT_FOUND, command.correlationId());
        }

        UserAccount user = identity.user();
        if (!user.status().canAuthenticate()) {
            passwordHasher.matches(password, user.passwordHash());
            AuthenticationEvent.Reason reason = user.status() == UserStatus.BLOQUEADO
                    ? AuthenticationEvent.Reason.USER_BLOCKED
                    : AuthenticationEvent.Reason.USER_INACTIVE;
            reject(tenant.id(), user.id(), reason, command.correlationId());
        }
        if (!passwordHasher.matches(password, user.passwordHash())) {
            reject(tenant.id(), user.id(), AuthenticationEvent.Reason.BAD_PASSWORD, command.correlationId());
        }

        UUID branchId = identity.branchId();
        if (branchId != null && branchRepository.findByTenantIdAndId(tenant.id(), branchId)
                .filter(branch -> branch.active())
                .isEmpty()) {
            reject(tenant.id(), user.id(), AuthenticationEvent.Reason.BRANCH_INVALID, command.correlationId());
        }

        Set<String> authorities = identityRepository.findEffectiveAuthorities(tenant.id(), user.id());
        TokenSubject subject = new TokenSubject(user.id(), tenant.id(), branchId, authorities);
        IssuedAccessToken token = accessTokenIssuer.issue(subject);
        Instant now = clock.instant();
        identityRepository.updateLastAccess(tenant.id(), user.id(), now);
        eventWriter.append(event(
                tenant.id(),
                user.id(),
                AuthenticationEvent.Result.SUCCESS,
                AuthenticationEvent.Reason.AUTHENTICATED,
                now,
                command.correlationId()));

        return new LoginResult(
                token.value(),
                token.expiresAt(),
                user.id(),
                tenant.id(),
                branchId,
                authorities);
    }

    private void reject(UUID tenantId, UUID userId, AuthenticationEvent.Reason reason, UUID correlationId) {
        eventWriter.append(event(
                tenantId,
                userId,
                AuthenticationEvent.Result.FAILURE,
                reason,
                clock.instant(),
                correlationId));
        throw new InvalidCredentialsException();
    }

    private static AuthenticationEvent event(
            UUID tenantId,
            UUID userId,
            AuthenticationEvent.Result result,
            AuthenticationEvent.Reason reason,
            Instant occurredAt,
            UUID correlationId) {
        return new AuthenticationEvent(
                UUID.randomUUID(),
                tenantId,
                userId,
                AuthenticationEvent.Type.LOGIN,
                result,
                reason,
                occurredAt,
                correlationId);
    }

    private static String normalizeSlug(String slug) {
        if (slug == null) {
            throw new IllegalArgumentException("Clinic slug is required");
        }
        return slug.trim();
    }
}
