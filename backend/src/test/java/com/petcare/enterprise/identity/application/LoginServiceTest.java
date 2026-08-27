package com.petcare.enterprise.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserAccount;
import com.petcare.enterprise.identity.domain.UserStatus;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.TenantRepository;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.Tenant;
import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();
    private static final UUID CORRELATION_ID = UUID.randomUUID();

    private final StubTenantRepository tenants = new StubTenantRepository();
    private final StubBranchRepository branches = new StubBranchRepository();
    private final StubIdentityRepository identities = new StubIdentityRepository();
    private final StubPasswordHasher passwords = new StubPasswordHasher();
    private final RecordingEventWriter events = new RecordingEventWriter();
    private LoginService service;

    @BeforeEach
    void setUp() {
        tenants.tenant = tenant(TenantStatus.ACTIVO);
        branches.branch = branch(TENANT_ID);
        identities.identity = identity(UserStatus.ACTIVO, BRANCH_ID);
        identities.authorities = Set.of("DASHBOARD_VIEW");
        service = new LoginService(
                tenants,
                branches,
                identities,
                passwords,
                subject -> new IssuedAccessToken("signed-test-token", NOW.plusSeconds(900)),
                events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authenticatesValidUserAndResolvesTrustedAuthoritiesAndBranch() {
        LoginResult result = service.login(command("correct-password"));

        assertThat(result.accessToken()).isEqualTo("signed-test-token");
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.branchId()).isEqualTo(BRANCH_ID);
        assertThat(result.authorities()).containsExactly("DASHBOARD_VIEW");
        assertThat(identities.lastAccessAt).isEqualTo(NOW);
        assertThat(events.saved).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticationEvent.Result.SUCCESS);
            assertThat(event.reason()).isEqualTo(AuthenticationEvent.Reason.AUTHENTICATED);
            assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        });
    }

    @Test
    void acceptsUserWithoutAssignedBranch() {
        identities.identity = identity(UserStatus.ACTIVO, null);

        assertThat(service.login(command("correct-password")).branchId()).isNull();
    }

    @Test
    void tenantMissingAndInactiveReturnSameExternalExceptionWithDifferentInternalReasons() {
        tenants.tenant = null;
        assertRejected(AuthenticationEvent.Reason.TENANT_NOT_FOUND);

        events.saved.clear();
        tenants.tenant = tenant(TenantStatus.SUSPENDIDO);
        assertRejected(AuthenticationEvent.Reason.TENANT_INACTIVE);
    }

    @Test
    void userMissingInactiveBlockedAndBadPasswordReturnSameExternalException() {
        identities.identity = null;
        assertRejected(AuthenticationEvent.Reason.USER_NOT_FOUND);

        events.saved.clear();
        identities.identity = identity(UserStatus.INACTIVO, BRANCH_ID);
        assertRejected(AuthenticationEvent.Reason.USER_INACTIVE);

        events.saved.clear();
        identities.identity = identity(UserStatus.BLOQUEADO, BRANCH_ID);
        assertRejected(AuthenticationEvent.Reason.USER_BLOCKED);

        events.saved.clear();
        identities.identity = identity(UserStatus.ACTIVO, BRANCH_ID);
        assertThatThrownBy(() -> service.login(command("wrong-password")))
                .isExactlyInstanceOf(InvalidCredentialsException.class);
        assertThat(events.saved).singleElement()
                .extracting(AuthenticationEvent::reason)
                .isEqualTo(AuthenticationEvent.Reason.BAD_PASSWORD);
    }

    @Test
    void rejectsBranchThatDoesNotBelongToTenant() {
        branches.branch = branch(UUID.randomUUID());
        assertRejected(AuthenticationEvent.Reason.BRANCH_INVALID);
    }

    private void assertRejected(AuthenticationEvent.Reason reason) {
        assertThatThrownBy(() -> service.login(command("correct-password")))
                .isExactlyInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");
        assertThat(events.saved).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticationEvent.Result.FAILURE);
            assertThat(event.reason()).isEqualTo(reason);
            assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        });
    }

    private static LoginCommand command(String password) {
        return new LoginCommand("fictional-clinic", "ADMIN@EXAMPLE.TEST", password, CORRELATION_ID);
    }

    private static Tenant tenant(TenantStatus status) {
        return Tenant.create(
                TENANT_ID,
                "Fictional Clinic",
                null,
                "fictional-clinic",
                TenantPlan.STARTER,
                status,
                NOW);
    }

    private static Branch branch(UUID tenantId) {
        return Branch.restore(
                BRANCH_ID,
                tenantId,
                "Main Branch",
                null,
                null,
                "America/Lima",
                true,
                NOW);
    }

    private static LoginIdentity identity(UserStatus status, UUID branchId) {
        return new LoginIdentity(new UserAccount(
                USER_ID,
                TENANT_ID,
                "Alex",
                "Fictional",
                new EmailAddress("admin@example.test"),
                "hash:correct-password",
                status,
                null,
                NOW), branchId);
    }

    private static final class StubTenantRepository implements TenantRepository {
        private Tenant tenant;

        @Override
        public boolean existsBySlug(String slug) {
            return tenant != null;
        }

        @Override
        public void save(Tenant tenant) {
            this.tenant = tenant;
        }

        @Override
        public Optional<Tenant> findBySlugForAuthentication(String slug) {
            return Optional.ofNullable(tenant);
        }
    }

    private static final class StubBranchRepository implements BranchRepository {
        private Branch branch;

        @Override
        public void saveForPlatformRegistration(Branch branch) {
            this.branch = branch;
        }

        @Override
        public Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId) {
            return Optional.ofNullable(branch)
                    .filter(value -> value.tenantId().equals(tenantId) && value.id().equals(branchId));
        }

        @Override
        public List<Branch> findAllByTenantId(UUID tenantId) {
            return Optional.ofNullable(branch)
                    .filter(value -> value.tenantId().equals(tenantId))
                    .stream()
                    .toList();
        }

        @Override
        public Optional<Branch> findByTenantIdAndName(UUID tenantId, String name) {
            return Optional.ofNullable(branch)
                    .filter(value -> value.tenantId().equals(tenantId) && value.name().equals(name));
        }
    }

    private static final class StubIdentityRepository implements IdentityRepository {
        private LoginIdentity identity;
        private Set<String> authorities = Set.of();
        private Instant lastAccessAt;

        @Override
        public Optional<LoginIdentity> findForLogin(UUID tenantId, EmailAddress email) {
            return Optional.ofNullable(identity);
        }

        @Override
        public Set<String> findEffectiveAuthorities(UUID tenantId, UUID userId) {
            return new LinkedHashSet<>(authorities);
        }

        @Override
        public void updateLastAccess(UUID tenantId, UUID userId, Instant lastAccessAt) {
            this.lastAccessAt = lastAccessAt;
        }

        @Override
        public BootstrapIdentityResult ensureBootstrapIdentity(BootstrapIdentity command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubPasswordHasher implements PasswordHasher {
        @Override
        public String hash(CharSequence rawPassword) {
            return "hash:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String passwordHash) {
            return passwordHash.equals("hash:" + rawPassword);
        }
    }

    private static final class RecordingEventWriter implements AuthenticationEventWriter {
        private final List<AuthenticationEvent> saved = new ArrayList<>();

        @Override
        public void append(AuthenticationEvent event) {
            saved.add(event);
        }
    }
}
