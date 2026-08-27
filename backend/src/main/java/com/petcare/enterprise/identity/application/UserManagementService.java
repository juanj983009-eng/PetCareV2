package com.petcare.enterprise.identity.application;

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
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserOptions;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserPage;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserSearch;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserStatus;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.TenantContext;
import com.petcare.enterprise.tenancy.domain.Branch;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService implements UserManagementUseCase {

    private static final String ENTITY_TYPE = "USER";

    private final TenantContext tenantContext;
    private final UserManagementRepository users;
    private final BranchRepository branches;
    private final PasswordHasher passwordHasher;
    private final AuditMetadataProvider auditMetadataProvider;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public UserManagementService(
            TenantContext tenantContext,
            UserManagementRepository users,
            BranchRepository branches,
            PasswordHasher passwordHasher,
            AuditMetadataProvider auditMetadataProvider,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.tenantContext = tenantContext;
        this.users = users;
        this.branches = branches;
        this.passwordHasher = passwordHasher;
        this.auditMetadataProvider = auditMetadataProvider;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserPage list(UserSearch search) {
        Objects.requireNonNull(search, "User search is required");
        return users.findAll(tenantContext.requireTenant().tenantId(), search);
    }

    @Override
    @Transactional(readOnly = true)
    public ManagedUser get(UUID userId) {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        return requireUser(tenantId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public UserOptions options() {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        List<RoleOption> roleOptions = users.findRolesByTenantId(tenantId);
        List<BranchOption> branchOptions = branches.findAllByTenantId(tenantId).stream()
                .map(UserManagementService::branchOption)
                .toList();
        return new UserOptions(roleOptions, branchOptions);
    }

    @Override
    @Transactional
    public ManagedUser create(CreateUser command) {
        Objects.requireNonNull(command, "Create user command is required");
        AuditMetadata metadata = auditMetadataProvider.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        RoleOption role = requireRole(tenantId, command.roleId());
        Branch branch = requireBranch(tenantId, command.branchId());
        EmailAddress email = new EmailAddress(command.email());
        if (users.existsByTenantIdAndEmail(tenantId, email, null)) {
            throw UserManagementException.emailConflict();
        }
        String passwordHash = passwordHasher.hash(Objects.requireNonNull(command.initialPassword(), "Password is required"));
        Instant now = clock.instant();
        ManagedUser created = users.create(new NewUser(
                UUID.randomUUID(),
                tenantId,
                command.firstName(),
                command.lastName(),
                email,
                passwordHash,
                role.id(),
                branch.id(),
                now));
        appendAudit(metadata, created.id(), "CREATE", null, auditData(created));
        return created;
    }

    @Override
    @Transactional
    public ManagedUser update(UUID userId, UpdateUser command) {
        Objects.requireNonNull(command, "Update user command is required");
        AuditMetadata metadata = auditMetadataProvider.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        ManagedUser previous = requireUser(tenantId, userId);
        RoleOption role = requireRole(tenantId, command.roleId());
        Branch branch = requireBranch(tenantId, command.branchId());
        EmailAddress email = new EmailAddress(command.email());
        if (users.existsByTenantIdAndEmail(tenantId, email, userId)) {
            throw UserManagementException.emailConflict();
        }
        ManagedUser updated = users.update(new UpdatedUser(
                        userId,
                        tenantId,
                        command.firstName(),
                        command.lastName(),
                        email,
                        role.id(),
                        branch.id(),
                        command.version(),
                        clock.instant()))
                .orElseThrow(UserManagementException::concurrentConflict);

        appendAudit(metadata, userId, "UPDATE", auditData(previous), auditData(updated));
        if (!Objects.equals(optionId(previous.role()), optionId(updated.role()))) {
            appendAudit(metadata, userId, "ROLE_CHANGED", roleData(previous), roleData(updated));
        }
        if (!Objects.equals(optionId(previous.branch()), optionId(updated.branch()))) {
            appendAudit(metadata, userId, "BRANCH_CHANGED", branchData(previous), branchData(updated));
        }
        return updated;
    }

    @Override
    @Transactional
    public ManagedUser activate(UUID userId, long version) {
        return changeStatus(userId, version, UserStatus.ACTIVO, "ACTIVATE");
    }

    @Override
    @Transactional
    public ManagedUser deactivate(UUID userId, long version) {
        return changeStatus(userId, version, UserStatus.INACTIVO, "DEACTIVATE");
    }

    private ManagedUser changeStatus(UUID userId, long version, UserStatus target, String action) {
        AuditMetadata metadata = auditMetadataProvider.requireTenantMetadata();
        ManagedUser previous = requireUser(metadata.tenantId(), userId);
        if (previous.status() == UserStatus.BLOQUEADO) {
            throw UserManagementException.blockedTransition();
        }
        if (previous.status() == target) {
            return previous;
        }
        ManagedUser updated = users.updateStatus(
                        metadata.tenantId(), userId, target, version, clock.instant())
                .orElseThrow(UserManagementException::concurrentConflict);
        appendAudit(metadata, userId, action, statusData(previous), statusData(updated));
        return updated;
    }

    private ManagedUser requireUser(UUID tenantId, UUID userId) {
        return users.findByTenantIdAndId(tenantId, Objects.requireNonNull(userId, "User id is required"))
                .orElseThrow(UserManagementException::userNotFound);
    }

    private RoleOption requireRole(UUID tenantId, UUID roleId) {
        return users.findRoleByTenantIdAndId(tenantId, Objects.requireNonNull(roleId, "Role id is required"))
                .orElseThrow(UserManagementException::roleNotFound);
    }

    private Branch requireBranch(UUID tenantId, UUID branchId) {
        return branches.findByTenantIdAndId(tenantId, Objects.requireNonNull(branchId, "Branch id is required"))
                .orElseThrow(UserManagementException::branchNotFound);
    }

    private void appendAudit(
            AuditMetadata metadata,
            UUID userId,
            String action,
            Map<String, Object> oldData,
            Map<String, Object> newData) {
        auditLogWriter.append(new AuditRecord(
                UUID.randomUUID(),
                metadata.tenantId(),
                metadata.actorUserId(),
                metadata.branchId(),
                ENTITY_TYPE,
                userId,
                action,
                clock.instant(),
                null,
                metadata.correlationId(),
                oldData,
                newData));
    }

    private static Map<String, Object> auditData(ManagedUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("firstName", user.firstName());
        data.put("lastName", user.lastName());
        data.put("email", user.email());
        data.put("status", user.status().name());
        data.put("roleId", stringId(optionId(user.role())));
        data.put("branchId", stringId(optionId(user.branch())));
        return data;
    }

    private static Map<String, Object> roleData(ManagedUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleId", stringId(optionId(user.role())));
        data.put("roleName", user.role() == null ? null : user.role().name());
        return data;
    }

    private static Map<String, Object> branchData(ManagedUser user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branchId", stringId(optionId(user.branch())));
        data.put("branchName", user.branch() == null ? null : user.branch().name());
        return data;
    }

    private static Map<String, Object> statusData(ManagedUser user) {
        return Map.of("status", user.status().name());
    }

    private static BranchOption branchOption(Branch branch) {
        return new BranchOption(branch.id(), branch.name(), branch.active());
    }

    private static UUID optionId(RoleOption option) {
        return option == null ? null : option.id();
    }

    private static UUID optionId(BranchOption option) {
        return option == null ? null : option.id();
    }

    private static String stringId(UUID id) {
        return id == null ? null : id.toString();
    }
}
