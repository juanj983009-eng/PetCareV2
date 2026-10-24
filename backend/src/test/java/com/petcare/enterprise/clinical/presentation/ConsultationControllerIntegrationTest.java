package com.petcare.enterprise.clinical.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository;
import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ConsultationControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AuditLogWriter auditLogWriter;

    @MockitoSpyBean
    ConsultationRepository consultationRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @AfterEach
    void cleanUpTenants() {
        reset(auditLogWriter, consultationRepository);
        if (!createdTenantIds.isEmpty()) {
            jdbc.execute("ALTER TABLE audit_entries DISABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE appointment_status_history DISABLE TRIGGER USER");
            try {
                for (UUID tenantId : createdTenantIds) {
                    jdbc.update("DELETE FROM audit_entries WHERE tenant_id = ?", tenantId);
                    jdbc.update("DELETE FROM appointment_status_history WHERE tenant_id = ?", tenantId);
                }
            } finally {
                jdbc.execute("ALTER TABLE appointment_status_history ENABLE TRIGGER USER");
                jdbc.execute("ALTER TABLE audit_entries ENABLE TRIGGER USER");
            }
        }
        for (UUID tenantId : createdTenantIds) {
            jdbc.update("DELETE FROM consultations WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM appointments WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM medical_records WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM patients WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM owners WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM user_roles WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM user_branches WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM role_permissions WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM roles WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM branches WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        }
        createdTenantIds.clear();
    }

    @Test
    void provisionsConsultationCreatePermissionIdempotentlyOnlyToVeterinario() {
        TenantFixture tenant = createTenantFixture("consulta-rbac");
        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenant.tenantId);

        Integer permCount = jdbc.queryForObject(
                "SELECT count(*) FROM permissions WHERE code = 'CONSULTATION_CREATE' AND module = 'CLINICAL'",
                Integer.class);
        assertThat(permCount).isEqualTo(1);

        List<String> rolesWithPermission = jdbc.queryForList("""
                SELECT upper(r.name)
                FROM role_permissions rp
                JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.tenant_id = ? AND p.code = 'CONSULTATION_CREATE'
                ORDER BY upper(r.name)
                """, String.class, tenant.tenantId);

        assertThat(rolesWithPermission).containsExactly("VETERINARIO");
    }

    @Test
    void startsConsultationFromWaitingAppointmentAtomicallyAndSupportsIdempotentRetry() throws Exception {
        TenantFixture tenant = createTenantFixture("consulta-atomic");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Elena",
                "Rojas",
                "elena@petcare.test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Carlos", "Mendoza", "DNI", "41223344");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Thor",
                "CANINO",
                "Husky");

        UUID appointmentId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                Instant.parse("2026-09-28T14:00:00Z"),
                Instant.parse("2026-09-28T14:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Decaimiento",
                "Controlar temperatura");

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult firstResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
                .andExpect(jsonPath("$.appointmentStatus").value("EN_ATENCION"))
                .andExpect(jsonPath("$.patientId").value(patient.patientId.toString()))
                .andExpect(jsonPath("$.patientName").value("Thor"))
                .andExpect(jsonPath("$.medicalRecordId").value(patient.medicalRecordId.toString()))
                .andExpect(jsonPath("$.branchId").value(tenant.branchId.toString()))
                .andExpect(jsonPath("$.branchName").value("Sede Principal"))
                .andExpect(jsonPath("$.veterinarianId").value(vetId.toString()))
                .andExpect(jsonPath("$.veterinarianFullName").value("Dra. Elena Rojas"))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andReturn();

        UUID consultationId = jdbc.queryForObject(
                "SELECT id FROM consultations WHERE tenant_id = ? AND appointment_id = ?",
                UUID.class,
                tenant.tenantId,
                appointmentId);
        assertThat(firstResult.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/consultations/" + consultationId);

        String appointmentStatus = jdbc.queryForObject(
                "SELECT status FROM appointments WHERE tenant_id = ? AND id = ?",
                String.class,
                tenant.tenantId,
                appointmentId);
        assertThat(appointmentStatus).isEqualTo("EN_ATENCION");

        // Verify draft consultation allows null reason and clinical assessment fields (HU-021)
        Integer draftCheck = jdbc.queryForObject("""
                SELECT count(*)
                FROM consultations
                WHERE tenant_id = ? AND id = ?
                  AND status = 'BORRADOR'
                  AND reason IS NULL
                  AND anamnesis IS NULL
                  AND physical_exam IS NULL
                  AND clinical_details IS NULL
                  AND plan IS NULL
                """, Integer.class, tenant.tenantId, consultationId);
        assertThat(draftCheck).isEqualTo(1);

        // Retry via appointment subresource returns existing consultation without duplicating audit/history
        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/consultations/" + consultationId))
                .andExpect(jsonPath("$.id").value(consultationId.toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.appointmentStatus").value("EN_ATENCION"));

        Integer consultationCount = jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId);
        Integer historyCount = jdbc.queryForObject(
                "SELECT count(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ? AND previous_status = 'EN_ESPERA' AND new_status = 'EN_ATENCION'",
                Integer.class,
                tenant.tenantId,
                appointmentId);
        Integer startedAuditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_type = 'CONSULTATION' AND entity_id = ? AND action = 'CONSULTATION_STARTED'",
                Integer.class,
                tenant.tenantId,
                consultationId);
        Integer statusAuditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_type = 'APPOINTMENT' AND entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'",
                Integer.class,
                tenant.tenantId,
                appointmentId);

        assertThat(consultationCount).isEqualTo(1);
        assertThat(historyCount).isEqualTo(1);
        assertThat(startedAuditCount).isEqualTo(1);
        assertThat(statusAuditCount).isEqualTo(1);
    }

    @Test
    void handlesConcurrentConsultationRequestsReturningSingleConsultationAndSingleAudit() throws Exception {
        TenantFixture tenant = createTenantFixture("consulta-concurrente");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Marco",
                "Silva",
                "marco@petcare.test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Lucia", "Gomez", "DNI", "45566778");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Luna",
                "FELINO",
                "Siames");

        UUID appointmentId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                Instant.parse("2026-09-28T15:00:00Z"),
                Instant.parse("2026-09-28T15:30:00Z"),
                "CONTROL",
                "EN_ESPERA",
                "Control postoperatorio",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<Integer> task = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                                .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            futures.add(pool.submit(task));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses).allMatch(code -> code == 201 || code == 200);
        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1L);

        Integer consultationCount = jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId);
        Integer historyCount = jdbc.queryForObject(
                "SELECT count(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId);
        Integer startedAuditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_type = 'CONSULTATION' AND action = 'CONSULTATION_STARTED'",
                Integer.class,
                tenant.tenantId);

        assertThat(consultationCount).isEqualTo(1);
        assertThat(historyCount).isEqualTo(1);
        assertThat(startedAuditCount).isEqualTo(1);
    }

    @Test
    void rollsBackAppointmentTransitionAndStatusHistoryWhenConsultationInsertOrAuditFails() throws Exception {
        TenantFixture tenant = createTenantFixture("consulta-rollback");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Diego",
                "Paredes",
                "diego@petcare.test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Rosa", "Vargas", "DNI", "48899001");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Rocky",
                "CANINO",
                "Boxer");

        UUID appointmentId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                Instant.parse("2026-09-28T16:00:00Z"),
                Instant.parse("2026-09-28T16:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Chequeo",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        // Case 1: Consultation insert fails -> appointment transition rolls back
        doThrow(new RuntimeException("Simulated consultation persistence failure"))
                .when(consultationRepository)
                .insert(any());

        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM appointments WHERE tenant_id = ? AND id = ?",
                String.class,
                tenant.tenantId,
                appointmentId)).isEqualTo("EN_ESPERA");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId)).isZero();

        reset(consultationRepository, auditLogWriter);

        // Case 2: Audit writer fails on CONSULTATION_STARTED -> both consultation and appointment transition roll back
        doAnswer(invocation -> {
            AuditRecord record = invocation.getArgument(0);
            if ("CONSULTATION_STARTED".equals(record.action())) {
                throw new RuntimeException("Simulated consultation audit failure");
            }
            return invocation.callRealMethod();
        }).when(auditLogWriter).append(any());

        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM appointments WHERE tenant_id = ? AND id = ?",
                String.class,
                tenant.tenantId,
                appointmentId)).isEqualTo("EN_ESPERA");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM consultations WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appointmentId)).isZero();
    }

    @Test
    void enforcesAuthenticationPermissionAssignedVeterinarianTenantIsolationAndValidEntities() throws Exception {
        TenantFixture tenantA = createTenantFixture("consulta-sec-a");
        TenantFixture tenantB = createTenantFixture("consulta-sec-b");

        UUID vetAssigned = insertUserWithRoleAndBranch(
                tenantA.tenantId,
                tenantA.branchId,
                "Dra. Sofia",
                "Castro",
                "sofia@petcare.test",
                "VETERINARIO");
        UUID vetOther = insertUserWithRoleAndBranch(
                tenantA.tenantId,
                tenantA.branchId,
                "Dr. Jorge",
                "Mora",
                "jorge@petcare.test",
                "VETERINARIO");
        UUID vetTenantB = insertUserWithRoleAndBranch(
                tenantB.tenantId,
                tenantB.branchId,
                "Dr. Bruno",
                "Diaz",
                "bruno@petcare.test",
                "VETERINARIO");

        UUID ownerA = insertOwner(tenantA.tenantId, "Pedro", "Salazar", "DNI", "40011223");
        PatientWithMedicalRecord activePatient = insertPatientWithMedicalRecord(
                tenantA.tenantId,
                ownerA,
                "Max",
                "CANINO",
                "Golden");

        UUID waitingAppointment = insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                activePatient.patientId,
                vetAssigned,
                Instant.parse("2026-09-28T17:00:00Z"),
                Instant.parse("2026-09-28T17:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Consulta general",
                null);

        // 1. Unauthenticated -> 401 UNAUTHORIZED
        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", waitingAppointment))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 2. Receptionist/Admin without CONSULTATION_CREATE -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", waitingAppointment)
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ", "APPOINTMENT_STATUS_UPDATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 3. Another veterinarian in the same tenant not assigned to this appointment -> 403 ACCESS_DENIED
        TenantFixture unassignedVetActor = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetOther);
        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", waitingAppointment)
                        .with(authentication(auth(unassignedVetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 4. Veterinarian from another tenant -> 404 RESOURCE_NOT_FOUND (uniform with missing UUID)
        TenantFixture otherTenantVetActor = new TenantFixture(tenantB.tenantId, tenantB.branchId, vetTenantB);
        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", waitingAppointment)
                        .with(authentication(auth(otherTenantVetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", UUID.randomUUID())
                        .with(authentication(auth(otherTenantVetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 5. Incompatible appointment states (PROGRAMADA, CONFIRMADA, FINALIZADA, CANCELADA, NO_ASISTIO) -> 409
        TenantFixture assignedVetActor = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetAssigned);
        int hourOffset = 18;
        for (String invalidStatus : List.of("PROGRAMADA", "CONFIRMADA", "FINALIZADA", "CANCELADA", "NO_ASISTIO")) {
            Instant start = Instant.parse(String.format("2026-09-28T%02d:00:00Z", hourOffset));
            Instant end = start.plusSeconds(1800);
            hourOffset += 1;
            UUID invalidApptId = insertAppointment(
                    tenantA.tenantId,
                    tenantA.branchId,
                    activePatient.patientId,
                    vetAssigned,
                    start,
                    end,
                    "CONTROL",
                    invalidStatus,
                    "Motivo",
                    null);

            mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", invalidApptId)
                            .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));
        }

        // 6. Patient without medical record or inactive patient -> 404 RESOURCE_NOT_FOUND
        UUID patientWithoutRecord = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients (id, tenant_id, owner_id, name, species, breed, sex, status)
                VALUES (?, ?, ?, 'Milo', 'CANINO', 'Beagle', 'MACHO', 'ACTIVO')
                """, patientWithoutRecord, tenantA.tenantId, ownerA);

        UUID apptWithoutMedicalRecord = insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                patientWithoutRecord,
                vetAssigned,
                Instant.parse("2026-09-29T14:00:00Z"),
                Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Sin historia",
                null);

        mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", apptWithoutMedicalRecord)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM appointments WHERE tenant_id = ? AND id = ?",
                String.class,
                tenantA.tenantId,
                apptWithoutMedicalRecord)).isEqualTo("EN_ESPERA");
    }

    private TenantFixture createTenantFixture(String slugPrefix) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String slug = slugPrefix + "-" + tenantId.toString().substring(0, 8);

        jdbc.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, 'Clínica Test', ?, 'ENTERPRISE', 'ACTIVO', now())
                """, tenantId, slug);
        createdTenantIds.add(tenantId);

        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenantId);

        jdbc.update("""
                INSERT INTO branches (id, tenant_id, name, address, phone, time_zone, active, created_at)
                VALUES (?, ?, 'Sede Principal', 'Av. Central 100', '999000111', 'America/Lima', true, now())
                """, branchId, tenantId);

        jdbc.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at, updated_at)
                VALUES (?, ?, 'Admin', 'Test', ?, '$2a$10$abcdefghijklmnopqrstuv', 'ACTIVO', now(), now())
                """, userId, tenantId, "admin@" + slug + ".test");

        return new TenantFixture(tenantId, branchId, userId);
    }

    private UUID insertUserWithRoleAndBranch(
            UUID tenantId,
            UUID branchId,
            String firstName,
            String lastName,
            String email,
            String roleName) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '$2a$10$abcdefghijklmnopqrstuv', 'ACTIVO', now(), now())
                """, userId, tenantId, firstName, lastName, email);

        jdbc.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantId, userId, branchId);

        UUID roleId = jdbc.query(
                "SELECT id FROM roles WHERE tenant_id = ? AND upper(name) = upper(?)",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                tenantId,
                roleName).stream().findFirst().orElse(null);

        if (roleId == null) {
            roleId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO roles (id, tenant_id, name, description, system, created_at)
                    VALUES (?, ?, ?, 'Rol de prueba', true, now())
                    """, roleId, tenantId, roleName);
        }
        jdbc.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, userId, roleId);
        return userId;
    }

    private UUID insertOwner(UUID tenantId, String firstName, String lastName, String docType, String docNumber) {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO owners (id, tenant_id, first_name, last_name, document_type, document_number, phone, active)
                VALUES (?, ?, ?, ?, ?, ?, '999888777', true)
                """, ownerId, tenantId, firstName, lastName, docType, docNumber);
        return ownerId;
    }

    private PatientWithMedicalRecord insertPatientWithMedicalRecord(
            UUID tenantId,
            UUID ownerId,
            String name,
            String species,
            String breed) {
        UUID patientId = UUID.randomUUID();
        UUID medicalRecordId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients (id, tenant_id, owner_id, name, species, breed, sex, status)
                VALUES (?, ?, ?, ?, ?, ?, 'MACHO', 'ACTIVO')
                """, patientId, tenantId, ownerId, name, species, breed);
        jdbc.update("""
                INSERT INTO medical_records (id, tenant_id, patient_id, created_at)
                VALUES (?, ?, ?, now())
                """, medicalRecordId, tenantId, patientId);
        return new PatientWithMedicalRecord(patientId, medicalRecordId);
    }

    private UUID insertAppointment(
            UUID tenantId,
            UUID branchId,
            UUID patientId,
            UUID veterinarianId,
            Instant startsAt,
            Instant endsAt,
            String appointmentType,
            String status,
            String reason,
            String notes) {
        UUID id = UUID.randomUUID();
        boolean cancelled = "CANCELADA".equals(status);
        jdbc.update("""
                INSERT INTO appointments (
                    id, tenant_id, branch_id, patient_id, veterinarian_id,
                    starts_at, ends_at, appointment_type, status, reason, notes,
                    cancellation_reason, cancelled_at, cancelled_by,
                    created_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                id,
                tenantId,
                branchId,
                patientId,
                veterinarianId,
                Timestamp.from(startsAt),
                Timestamp.from(endsAt),
                appointmentType,
                status,
                reason,
                notes,
                cancelled ? "Cancelación previa de prueba" : null,
                cancelled ? Timestamp.from(startsAt) : null,
                cancelled ? veterinarianId : null,
                veterinarianId);
        return id;
    }

    private UsernamePasswordAuthenticationToken auth(TenantFixture f, String... permissions) {
        var authorities = List.of(permissions).stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(
                new PetCarePrincipal(f.userId, f.tenantId, f.branchId, authorities),
                null,
                authorities);
    }

    private record TenantFixture(UUID tenantId, UUID branchId, UUID userId) { }

    private record PatientWithMedicalRecord(UUID patientId, UUID medicalRecordId) { }
}
