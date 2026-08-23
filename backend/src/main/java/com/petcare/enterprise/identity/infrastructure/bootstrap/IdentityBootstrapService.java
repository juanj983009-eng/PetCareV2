package com.petcare.enterprise.identity.infrastructure.bootstrap;

import com.petcare.enterprise.identity.application.BootstrapIdentity;
import com.petcare.enterprise.identity.application.IdentityRepository;
import com.petcare.enterprise.identity.application.PasswordHasher;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.tenancy.application.RegisterTenantCommand;
import com.petcare.enterprise.tenancy.application.RegisterTenantUseCase;
import com.petcare.enterprise.tenancy.application.RegisteredTenant;
import com.petcare.enterprise.tenancy.application.TenantRepository;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.Tenant;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityBootstrapService {

    private final BootstrapProperties properties;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final RegisterTenantUseCase registerTenantUseCase;
    private final IdentityRepository identityRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    IdentityBootstrapService(
            BootstrapProperties properties,
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            RegisterTenantUseCase registerTenantUseCase,
            IdentityRepository identityRepository,
            PasswordHasher passwordHasher,
            Clock clock) {
        this.properties = properties;
        this.tenantRepository = tenantRepository;
        this.branchRepository = branchRepository;
        this.registerTenantUseCase = registerTenantUseCase;
        this.identityRepository = identityRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    void run() {
        validateConfiguration();
        TenantAndBranch target = resolveOrCreateTenantAndBranch();
        String passwordHash = passwordHasher.hash(properties.adminPassword());
        identityRepository.ensureBootstrapIdentity(new BootstrapIdentity(
                target.tenantId(),
                target.branchId(),
                properties.adminFirstName(),
                properties.adminLastName(),
                new EmailAddress(properties.adminEmail()),
                passwordHash,
                clock.instant()));
    }

    private TenantAndBranch resolveOrCreateTenantAndBranch() {
        Tenant existing = tenantRepository.findBySlugForAuthentication(properties.tenantSlug()).orElse(null);
        if (existing == null) {
            RegisteredTenant registered = registerTenantUseCase.register(new RegisterTenantCommand(
                    properties.tenantName(),
                    null,
                    properties.tenantSlug(),
                    properties.tenantPlan(),
                    TenantStatus.ACTIVO,
                    properties.branchName(),
                    null,
                    null,
                    properties.branchTimeZone(),
                    UUID.randomUUID()));
            return new TenantAndBranch(registered.id(), registered.mainBranch().id());
        }
        if (existing.status() != TenantStatus.ACTIVO) {
            throw new IllegalStateException("Bootstrap tenant must be active");
        }
        Branch branch = branchRepository.findByTenantIdAndName(existing.id(), properties.branchName())
                .filter(Branch::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Configured bootstrap branch does not exist or is inactive"));
        return new TenantAndBranch(existing.id(), branch.id());
    }

    private void validateConfiguration() {
        List<String> missing = new ArrayList<>();
        required(properties.tenantName(), "PETCARE_BOOTSTRAP_TENANT_NAME", missing);
        required(properties.tenantSlug(), "PETCARE_BOOTSTRAP_TENANT_SLUG", missing);
        if (properties.tenantPlan() == null) {
            missing.add("PETCARE_BOOTSTRAP_TENANT_PLAN");
        }
        required(properties.branchName(), "PETCARE_BOOTSTRAP_BRANCH_NAME", missing);
        required(properties.branchTimeZone(), "PETCARE_BOOTSTRAP_BRANCH_TIME_ZONE", missing);
        required(properties.adminFirstName(), "PETCARE_BOOTSTRAP_ADMIN_FIRST_NAME", missing);
        required(properties.adminLastName(), "PETCARE_BOOTSTRAP_ADMIN_LAST_NAME", missing);
        required(properties.adminEmail(), "PETCARE_BOOTSTRAP_ADMIN_EMAIL", missing);
        required(properties.adminPassword(), "PETCARE_BOOTSTRAP_ADMIN_PASSWORD", missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Incomplete bootstrap configuration: " + String.join(", ", missing));
        }
        if (properties.adminPassword().length() < 12) {
            throw new IllegalStateException("PETCARE_BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
        }
    }

    private static void required(String value, String variable, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(variable);
        }
    }

    private record TenantAndBranch(UUID tenantId, UUID branchId) {
    }
}
