package com.petcare.enterprise.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.identity.application.UserManagementRepository.NewUser;
import com.petcare.enterprise.identity.application.UserManagementRepository.UpdatedUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.BranchOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.CreateUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.ManagedUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.RoleOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UpdateUser;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserStatus;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.TenantContext;
import com.petcare.enterprise.tenancy.domain.Branch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ROLE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID BRANCH = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Mock private TenantContext tenantContext;
    @Mock private UserManagementRepository repository;
    @Mock private BranchRepository branches;
    @Mock private PasswordHasher passwordHasher;
    @Mock private AuditMetadataProvider metadataProvider;
    @Mock private AuditLogWriter auditLogWriter;

    private UserManagementService service;

    @BeforeEach
    void setUp() {
        service = new UserManagementService(
                tenantContext,
                repository,
                branches,
                passwordHasher,
                metadataProvider,
                auditLogWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(metadataProvider.requireTenantMetadata())
                .thenReturn(new AuditMetadata(TENANT, ACTOR, BRANCH, UUID.randomUUID()));
    }

    @Test
    void createsNormalizedHashedUserAndAuditsNoCredential() {
        when(repository.findRoleByTenantIdAndId(TENANT, ROLE)).thenReturn(Optional.of(role()));
        when(branches.findByTenantIdAndId(TENANT, BRANCH)).thenReturn(Optional.of(branch()));
        when(repository.existsByTenantIdAndEmail(TENANT, new EmailAddress("user@example.test"), null))
                .thenReturn(false);
        when(passwordHasher.hash("Fictional-Password-123!")).thenReturn("$2a$12$safehash");
        when(repository.create(any())).thenAnswer(invocation -> {
            NewUser value = invocation.getArgument(0);
            return managed(value.id(), value.email().value(), UserStatus.ACTIVO, 0, role(), branchOption());
        });

        ManagedUser created = service.create(new CreateUser(
                "  Ada ", " Prueba  ", " USER@Example.Test ",
                "Fictional-Password-123!", ROLE, BRANCH));

        ArgumentCaptor<NewUser> persisted = ArgumentCaptor.forClass(NewUser.class);
        verify(repository).create(persisted.capture());
        assertThat(persisted.getValue().email().value()).isEqualTo("user@example.test");
        assertThat(persisted.getValue().passwordHash()).startsWith("$2a$");
        assertThat(created.email()).isEqualTo("user@example.test");
        ArgumentCaptor<AuditRecord> audit = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLogWriter).append(audit.capture());
        assertThat(audit.getValue().newData()).doesNotContainKeys("password", "passwordHash", "token");
        assertThat(audit.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(audit.getValue().actorUserId()).isEqualTo(ACTOR);
    }

    @Test
    void rejectsDuplicateEmailBeforeHashing() {
        when(repository.findRoleByTenantIdAndId(TENANT, ROLE)).thenReturn(Optional.of(role()));
        when(branches.findByTenantIdAndId(TENANT, BRANCH)).thenReturn(Optional.of(branch()));
        when(repository.existsByTenantIdAndEmail(TENANT, new EmailAddress("same@example.test"), null))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUser(
                "Ada", "Prueba", "same@example.test", "Fictional-Password-123!", ROLE, BRANCH)))
                .isInstanceOf(UserManagementException.class)
                .extracting(error -> ((UserManagementException) error).kind())
                .isEqualTo(UserManagementException.Kind.EMAIL_CONFLICT);
        verify(passwordHasher, never()).hash(any());
    }

    @Test
    void rejectsRoleOrBranchOutsideTenantAsNotFound() {
        when(repository.findRoleByTenantIdAndId(TENANT, ROLE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(UserManagementException.class)
                .extracting(error -> ((UserManagementException) error).kind())
                .isEqualTo(UserManagementException.Kind.ROLE_NOT_FOUND);

        when(repository.findRoleByTenantIdAndId(TENANT, ROLE)).thenReturn(Optional.of(role()));
        when(branches.findByTenantIdAndId(TENANT, BRANCH)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(UserManagementException.class)
                .extracting(error -> ((UserManagementException) error).kind())
                .isEqualTo(UserManagementException.Kind.BRANCH_NOT_FOUND);
    }

    @Test
    void editPreservesPasswordBoundaryAndAuditsProfileRoleAndBranch() {
        RoleOption oldRole = new RoleOption(UUID.randomUUID(), "VETERINARIO");
        BranchOption oldBranch = new BranchOption(UUID.randomUUID(), "Anterior", true);
        ManagedUser previous = managed(USER, "old@example.test", UserStatus.ACTIVO, 4, oldRole, oldBranch);
        ManagedUser updated = managed(USER, "new@example.test", UserStatus.ACTIVO, 5, role(), branchOption());
        when(repository.findByTenantIdAndId(TENANT, USER)).thenReturn(Optional.of(previous));
        when(repository.findRoleByTenantIdAndId(TENANT, ROLE)).thenReturn(Optional.of(role()));
        when(branches.findByTenantIdAndId(TENANT, BRANCH)).thenReturn(Optional.of(branch()));
        when(repository.existsByTenantIdAndEmail(TENANT, new EmailAddress("new@example.test"), USER))
                .thenReturn(false);
        when(repository.update(any(UpdatedUser.class))).thenReturn(Optional.of(updated));

        service.update(USER, new UpdateUser("Nuevo", "Nombre", "new@example.test", ROLE, BRANCH, 4));

        verify(repository).update(any(UpdatedUser.class));
        verify(passwordHasher, never()).hash(any());
        ArgumentCaptor<AuditRecord> audits = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditLogWriter, org.mockito.Mockito.times(3)).append(audits.capture());
        assertThat(audits.getAllValues()).extracting(AuditRecord::action)
                .containsExactly("UPDATE", "ROLE_CHANGED", "BRANCH_CHANGED");
        assertThat(audits.getAllValues().toString()).doesNotContain("password", "$2a$", "token");
    }

    @Test
    void stateChangesAreExplicitAndIdempotentWhileBlockedIsUntouched() {
        ManagedUser active = managed(USER, "user@example.test", UserStatus.ACTIVO, 2, role(), branchOption());
        when(repository.findByTenantIdAndId(TENANT, USER)).thenReturn(Optional.of(active));
        assertThat(service.activate(USER, 2)).isSameAs(active);
        verify(repository, never()).updateStatus(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(auditLogWriter, never()).append(any());

        ManagedUser inactive = managed(USER, "user@example.test", UserStatus.INACTIVO, 3, role(), branchOption());
        when(repository.updateStatus(TENANT, USER, UserStatus.INACTIVO, 2, NOW))
                .thenReturn(Optional.of(inactive));
        assertThat(service.deactivate(USER, 2).status()).isEqualTo(UserStatus.INACTIVO);
        verify(auditLogWriter).append(any());

        when(repository.findByTenantIdAndId(TENANT, USER))
                .thenReturn(Optional.of(managed(USER, "user@example.test", UserStatus.BLOQUEADO, 7, role(), branchOption())));
        assertThatThrownBy(() -> service.activate(USER, 7))
                .isInstanceOf(UserManagementException.class)
                .extracting(error -> ((UserManagementException) error).kind())
                .isEqualTo(UserManagementException.Kind.BLOCKED_TRANSITION);
    }

    private static CreateUser command() {
        return new CreateUser(
                "Ada", "Prueba", "user@example.test", "Fictional-Password-123!", ROLE, BRANCH);
    }

    private static RoleOption role() {
        return new RoleOption(ROLE, "ADMINISTRADOR");
    }

    private static Branch branch() {
        return Branch.restore(BRANCH, TENANT, "Principal", null, null, "America/Lima", true, NOW);
    }

    private static BranchOption branchOption() {
        return new BranchOption(BRANCH, "Principal", true);
    }

    private static ManagedUser managed(
            UUID id,
            String email,
            UserStatus status,
            long version,
            RoleOption role,
            BranchOption branch) {
        return new ManagedUser(
                id, "Ada", "Prueba", email, status, role, branch, NOW, NOW, version);
    }
}
