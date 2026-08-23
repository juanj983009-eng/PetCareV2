package com.petcare.enterprise.tenancy.application;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterTenantService implements RegisterTenantUseCase {

    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RegisterTenantService(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            AuditLogWriter auditLogWriter,
            Clock clock) {
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RegisteredTenant register(RegisterTenantCommand command) {
        Objects.requireNonNull(command, "Register tenant command is required");
        UUID correlationId = Objects.requireNonNull(command.correlationId(), "Correlation id is required");

        if (tenantRepository.existsBySlug(command.slug())) {
            throw new TenantSlugAlreadyExistsException();
        }

        Instant now = clock.instant();
        Tenant tenant = Tenant.create(
                UUID.randomUUID(),
                command.name(),
                command.ruc(),
                command.slug(),
                command.plan(),
                command.status(),
                now);
        Branch branch = Branch.createMain(
                UUID.randomUUID(),
                tenant.id(),
                command.mainBranchName(),
                command.mainBranchAddress(),
                command.mainBranchPhone(),
                command.mainBranchTimeZone(),
                now);

        tenantRepository.save(tenant);
        branchRepository.save(tenant.id(), branch);
        auditLogWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenant.id(),
                null,
                branch.id(),
                "TENANT",
                tenant.id(),
                "TENANT_CREATED",
                now,
                null,
                correlationId,
                null,
                Map.of(
                        "slug", tenant.slug(),
                        "plan", tenant.plan().name(),
                        "status", tenant.status().name(),
                        "mainBranchId", branch.id().toString())));

        return new RegisteredTenant(
                tenant.id(),
                tenant.name(),
                tenant.ruc(),
                tenant.slug(),
                tenant.plan(),
                tenant.status(),
                tenant.createdAt(),
                new RegisteredTenant.RegisteredBranch(
                        branch.id(),
                        branch.name(),
                        branch.address(),
                        branch.phone(),
                        branch.timeZone().getId(),
                        branch.active(),
                        branch.createdAt()));
    }
}
