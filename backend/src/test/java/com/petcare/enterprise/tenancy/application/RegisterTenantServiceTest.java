package com.petcare.enterprise.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.tenancy.domain.Branch;
import com.petcare.enterprise.tenancy.domain.Tenant;
import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterTenantServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    void savesTenantBranchAndAuditWithTheSameScopeAndCorrelationId() {
        RecordingTenantRepository tenantRepository = new RecordingTenantRepository();
        RecordingBranchRepository branchRepository = new RecordingBranchRepository();
        RecordingAuditWriter auditWriter = new RecordingAuditWriter();
        UUID correlationId = UUID.randomUUID();
        RegisterTenantService service = service(tenantRepository, branchRepository, auditWriter);

        RegisteredTenant result = service.register(command("clinic-service-test", correlationId));

        assertThat(tenantRepository.saved.id()).isEqualTo(result.id());
        assertThat(branchRepository.saved.tenantId()).isEqualTo(result.id());
        assertThat(auditWriter.saved.tenantId()).isEqualTo(result.id());
        assertThat(auditWriter.saved.entityType()).isEqualTo("TENANT");
        assertThat(auditWriter.saved.entityId()).isEqualTo(result.id());
        assertThat(auditWriter.saved.action()).isEqualTo("TENANT_CREATED");
        assertThat(auditWriter.saved.correlationId()).isEqualTo(correlationId);
        assertThat(auditWriter.saved.actorUserId()).isNull();
        assertThat(auditWriter.saved.newData()).doesNotContainKeys("ruc", "password", "token");
    }

    @Test
    void rejectsAnExistingSlugBeforeWritingAnything() {
        RecordingTenantRepository tenantRepository = new RecordingTenantRepository();
        tenantRepository.slugExists = true;
        RecordingBranchRepository branchRepository = new RecordingBranchRepository();
        RecordingAuditWriter auditWriter = new RecordingAuditWriter();

        assertThatThrownBy(() -> service(tenantRepository, branchRepository, auditWriter)
                .register(command("existing-slug", UUID.randomUUID())))
                .isInstanceOf(TenantSlugAlreadyExistsException.class);

        assertThat(tenantRepository.saved).isNull();
        assertThat(branchRepository.saved).isNull();
        assertThat(auditWriter.saved).isNull();
    }

    private static RegisterTenantService service(
            TenantRepository tenantRepository,
            BranchRepository branchRepository,
            AuditLogWriter auditLogWriter) {
        return new RegisterTenantService(
                tenantRepository,
                branchRepository,
                auditLogWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RegisterTenantCommand command(String slug, UUID correlationId) {
        return new RegisterTenantCommand(
                "Clinic Service Test",
                null,
                slug,
                TenantPlan.STARTER,
                TenantStatus.ACTIVO,
                "Main Branch",
                null,
                null,
                "America/Lima",
                correlationId);
    }

    private static final class RecordingTenantRepository implements TenantRepository {
        private boolean slugExists;
        private Tenant saved;

        @Override
        public boolean existsBySlug(String slug) {
            return slugExists;
        }

        @Override
        public void save(Tenant tenant) {
            saved = tenant;
        }
    }

    private static final class RecordingBranchRepository implements BranchRepository {
        private Branch saved;

        @Override
        public void saveForPlatformRegistration(Branch branch) {
            saved = branch;
        }

        @Override
        public Optional<Branch> findByTenantIdAndId(UUID tenantId, UUID branchId) {
            return Optional.empty();
        }
    }

    private static final class RecordingAuditWriter implements AuditLogWriter {
        private AuditRecord saved;

        @Override
        public void append(AuditRecord record) {
            saved = record;
        }
    }
}
