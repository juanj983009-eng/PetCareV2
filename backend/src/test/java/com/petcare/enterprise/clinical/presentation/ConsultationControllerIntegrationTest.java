package com.petcare.enterprise.clinical.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository;
import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
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

    @Autowired
    ObjectMapper objectMapper;

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
    void provisionsConsultationPermissionsIdempotentlyOnlyToVeterinario() {
        TenantFixture tenant = createTenantFixture("consulta-rbac");
        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenant.tenantId);

        for (String permCode : List.of("CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE")) {
            Integer permCount = jdbc.queryForObject(
                    "SELECT count(*) FROM permissions WHERE code = ? AND module = 'CLINICAL'",
                    Integer.class,
                    permCode);
            assertThat(permCount).isEqualTo(1);

            List<String> rolesWithPermission = jdbc.queryForList("""
                    SELECT upper(r.name)
                    FROM role_permissions rp
                    JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND p.code = ?
                    ORDER BY upper(r.name)
                    """, String.class, tenant.tenantId, permCode);

            assertThat(rolesWithPermission).containsExactly("VETERINARIO");
        }
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

    @Test
    void savesClinicalEvaluationAndRetrievesViaGetWithStructuredAndVariableDetails() throws Exception {
        TenantFixture tenant = createTenantFixture("eval-save");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Camila",
                "Torres",
                "camila@petcare.test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Andrea", "Perez", "DNI", "47112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Boby",
                "CANINO",
                "Beagle");

        UUID appointmentId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                Instant.parse("2026-09-30T10:00:00Z"),
                Instant.parse("2026-09-30T10:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Control",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        // 1. Start consultation
        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // 2. Initial state has null clinical fields
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultationId.toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.anamnesis").isEmpty())
                .andExpect(jsonPath("$.physicalExam").isEmpty())
                .andExpect(jsonPath("$.vitalSigns").isEmpty())
                .andExpect(jsonPath("$.clinicalDetails").isEmpty());

        // 3. Save evaluation via PUT
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "reason", "  Chequeo anual por pérdida de apetito y letargo  ",
                "anamnesis", "Propietario refiere decaimiento desde hace 2 días, menor consumo de agua.",
                "physicalExam", "Mucosas ligeramente pálidas, dolor a la palpación abdominal leve.",
                "vitalSigns", Map.of(
                        "temperatureCelsius", 38.6,
                        "weightKg", 14.75,
                        "heartRateBpm", 115,
                        "respiratoryRateBpm", 26,
                        "capillaryRefillTimeSeconds", 1.8,
                        "mucousMembranes", "PALIDAS"
                ),
                "observations", "Observación clínica: sugiere ecografía abdominal preventiva.",
                "variableDetails", Map.of(
                        "hidratacion", "Leve deshidratación estimada 5%",
                        "condicionCorporal", "3/5",
                        "ganglios", "Sin linfoadenomegalia"
                )
        ));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultationId.toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.reason").value("Chequeo anual por pérdida de apetito y letargo"))
                .andExpect(jsonPath("$.anamnesis").value("Propietario refiere decaimiento desde hace 2 días, menor consumo de agua."))
                .andExpect(jsonPath("$.physicalExam").value("Mucosas ligeramente pálidas, dolor a la palpación abdominal leve."))
                .andExpect(jsonPath("$.vitalSigns.temperatureCelsius").value(38.6))
                .andExpect(jsonPath("$.vitalSigns.weightKg").value(14.75))
                .andExpect(jsonPath("$.vitalSigns.heartRateBpm").value(115))
                .andExpect(jsonPath("$.vitalSigns.respiratoryRateBpm").value(26))
                .andExpect(jsonPath("$.vitalSigns.capillaryRefillTimeSeconds").value(1.8))
                .andExpect(jsonPath("$.vitalSigns.mucousMembranes").value("PALIDAS"))
                .andExpect(jsonPath("$.clinicalDetails.observations").value("Observación clínica: sugiere ecografía abdominal preventiva."))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.hidratacion").value("Leve deshidratación estimada 5%"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.condicionCorporal").value("3/5"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.ganglios").value("Sin linfoadenomegalia"));

        // 4. Retrieve via GET and verify persistence
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(consultationId.toString()))
                .andExpect(jsonPath("$.status").value("BORRADOR"))
                .andExpect(jsonPath("$.reason").value("Chequeo anual por pérdida de apetito y letargo"))
                .andExpect(jsonPath("$.anamnesis").value("Propietario refiere decaimiento desde hace 2 días, menor consumo de agua."))
                .andExpect(jsonPath("$.physicalExam").value("Mucosas ligeramente pálidas, dolor a la palpación abdominal leve."))
                .andExpect(jsonPath("$.vitalSigns.temperatureCelsius").value(38.6))
                .andExpect(jsonPath("$.vitalSigns.weightKg").value(14.75))
                .andExpect(jsonPath("$.vitalSigns.heartRateBpm").value(115))
                .andExpect(jsonPath("$.vitalSigns.respiratoryRateBpm").value(26))
                .andExpect(jsonPath("$.vitalSigns.capillaryRefillTimeSeconds").value(1.8))
                .andExpect(jsonPath("$.vitalSigns.mucousMembranes").value("PALIDAS"))
                .andExpect(jsonPath("$.clinicalDetails.observations").value("Observación clínica: sugiere ecografía abdominal preventiva."))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.hidratacion").value("Leve deshidratación estimada 5%"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.condicionCorporal").value("3/5"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.ganglios").value("Sin linfoadenomegalia"));

        // 5. Verify audit entry: CONSULTATION_EVALUATION_SAVED without copying sensitive clinical details
        Integer evalAuditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_type = 'CONSULTATION' AND entity_id = ? AND action = 'CONSULTATION_EVALUATION_SAVED'",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(evalAuditCount).isEqualTo(1);

        String auditNewData = jdbc.queryForObject(
                "SELECT new_data::text FROM audit_entries WHERE tenant_id = ? AND entity_type = 'CONSULTATION' AND entity_id = ? AND action = 'CONSULTATION_EVALUATION_SAVED'",
                String.class,
                tenant.tenantId,
                consultationId);
        assertThat(auditNewData).doesNotContain("Chequeo anual");
        assertThat(auditNewData).doesNotContain("decaimiento");
        assertThat(auditNewData).doesNotContain("palpación");
        assertThat(auditNewData).doesNotContain("38.6");
        assertThat(auditNewData).doesNotContain("PALIDAS");
        assertThat(auditNewData).doesNotContain("ecografía");
        assertThat(auditNewData).contains(consultationId.toString());
        assertThat(auditNewData).contains("BORRADOR");
    }

    @Test
    void rejectsEvaluationWithoutReasonOrWithInvalidVitalSigns() throws Exception {
        TenantFixture tenant = createTenantFixture("eval-val");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Andres",
                "Luna",
                "andres@petcare.test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Soto", "DNI", "49001122");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Pelusa",
                "FELINO",
                "Persa");

        UUID appointmentId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                Instant.parse("2026-09-30T11:00:00Z"),
                Instant.parse("2026-09-30T11:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Vacunación",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // 1. Missing or blank reason -> 400 VALIDATION_FAILED
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 2. Temperature out of safe range (e.g. 25.0 °C or 50.0 °C) -> 400
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"temperatureCelsius\":25.0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"temperatureCelsius\":50.0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 3. Negative or zero weight -> 400
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"weightKg\":-2.5}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 4. Heart rate out of range (<20 or >400) -> 400
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"heartRateBpm\":500}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 5. Respiratory rate out of range (<5 or >200) -> 400
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"respiratoryRateBpm\":250}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 6. CRT out of range (<0.1 or >10.0) -> 400
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Control\",\"vitalSigns\":{\"capillaryRefillTimeSeconds\":15.0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void enforcesAssignedVeterinarianTenantIsolationAndStatusForEvaluation() throws Exception {
        TenantFixture tenantA = createTenantFixture("eval-sec-a");
        TenantFixture tenantB = createTenantFixture("eval-sec-b");

        UUID vetAssigned = insertUserWithRoleAndBranch(
                tenantA.tenantId,
                tenantA.branchId,
                "Dr. Raul",
                "Navarro",
                "raul@petcare.test",
                "VETERINARIO");
        UUID vetOther = insertUserWithRoleAndBranch(
                tenantA.tenantId,
                tenantA.branchId,
                "Dr. Gabriel",
                "Rios",
                "gabriel@petcare.test",
                "VETERINARIO");
        UUID vetTenantB = insertUserWithRoleAndBranch(
                tenantB.tenantId,
                tenantB.branchId,
                "Dra. Natalia",
                "Lozano",
                "natalia@petcare.test",
                "VETERINARIO");

        UUID ownerA = insertOwner(tenantA.tenantId, "Claudia", "Ruiz", "DNI", "41998877");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenantA.tenantId,
                ownerA,
                "Simba",
                "FELINO",
                "Angora");

        UUID appointmentId = insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                patient.patientId,
                vetAssigned,
                Instant.parse("2026-09-30T12:00:00Z"),
                Instant.parse("2026-09-30T12:30:00Z"),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Revisión",
                null);

        TenantFixture assignedVetActor = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetAssigned);
        TenantFixture unassignedVetActor = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetOther);
        TenantFixture otherTenantVetActor = new TenantFixture(tenantB.tenantId, tenantB.branchId, vetTenantB);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        String validBody = "{\"reason\":\"Chequeo dermatológico rutinario\"}";

        // 1. Unauthenticated -> 401 UNAUTHORIZED
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 2. User without CONSULTATION_UPDATE / CONSULTATION_READ -> 403 ACCESS_DENIED
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(assignedVetActor, "APPOINTMENT_READ"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 3. Different veterinarian in same tenant attempting PUT -> 403 ACCESS_DENIED
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(unassignedVetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 4. Veterinarian from different tenant -> 404 RESOURCE_NOT_FOUND (no tenant leakage)
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(otherTenantVetActor, "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(otherTenantVetActor, "CONSULTATION_READ", "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 5. Nonexistent UUID -> 404 RESOURCE_NOT_FOUND
        UUID randomId = UUID.randomUUID();
        mvc.perform(get("/api/v1/consultations/{consultationId}", randomId)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", randomId)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 6. When consultation is in state FINALIZADA -> 409 INVALID_CONSULTATION_STATUS
        jdbc.update("UPDATE consultations SET status = 'FINALIZADA' WHERE id = ?", consultationId);

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONSULTATION_STATUS"));

        // Verify consultation data in DB was NOT updated
        String currentReason = jdbc.queryForObject(
                "SELECT reason FROM consultations WHERE id = ?",
                String.class,
                consultationId);
        assertThat(currentReason).isNotEqualTo("Evaluación de control general.");

        // Verify no audit log was created for the failed evaluation
        verify(auditLogWriter, never()).append(argThat(record ->
                "CONSULTATION_EVALUATION_SAVED".equals(record.action())));
    }

    @Test
    void preservesExistingVariableDetailsWhenNotModifiedInEvaluation() throws Exception {
        TenantFixture tenant = createTenantFixture("eval-var-pres");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Paula", "Mora", "paula@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Carlos", "Silva", "DNI", "55112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Toby", "CANINO", "Poodle");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // 1. First save includes variableDetails
        String firstPayload = objectMapper.writeValueAsString(Map.of(
                "reason", "Primera evaluación con detalles",
                "observations", "Observación inicial",
                "variableDetails", Map.of("hidratacion", "optima", "comportamiento", "docil")
        ));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Primera evaluación con detalles"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.hidratacion").value("optima"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.comportamiento").value("docil"));

        // 2. Second save updates ONLY reason and vitalSigns, leaving variableDetails null in request
        String secondPayload = objectMapper.writeValueAsString(Map.of(
                "reason", "Segunda evaluación actualizada",
                "vitalSigns", Map.of("temperatureCelsius", 38.5, "weightKg", 12.0)
        ));

        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Segunda evaluación actualizada"))
                .andExpect(jsonPath("$.vitalSigns.temperatureCelsius").value(38.5))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.hidratacion").value("optima"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.comportamiento").value("docil"));

        // 3. GET retrieves the consultation with variableDetails completely intact
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.hidratacion").value("optima"))
                .andExpect(jsonPath("$.clinicalDetails.variableDetails.comportamiento").value("docil"));
    }

    @Test
    void reopeningAppointmentInAttentionReturnsExistingConsultationIdempotently() throws Exception {
        TenantFixture tenant = createTenantFixture("eval-resume");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dr. Jorge", "Vera", "jorge@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Lucia", "Ramos", "DNI", "66112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Rocky", "CANINO", "Bulldog");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-02T10:00:00Z"), Instant.parse("2026-10-02T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        // 1. First call transitions EN_ESPERA -> EN_ATENCION and creates consultation
        MvcResult firstCall = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/consultations/")))
                .andReturn();

        String consultationId = objectMapper.readTree(firstCall.getResponse().getContentAsString()).get("id").asText();

        // 2. Save evaluation in borrador
        String evalPayload = objectMapper.writeValueAsString(Map.of(
                "reason", "Evaluación guardada antes de salir de la consulta",
                "anamnesis", "Detalles clínicos registrados"
        ));
        mvc.perform(put("/api/v1/consultations/{consultationId}/evaluation", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evalPayload))
                .andExpect(status().isOk());

        // 3. User returns to schedule, appointment is in EN_ATENCION. Vet reopens it:
        MvcResult resumeCall = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/consultations/" + consultationId))
                .andReturn();

        String resumedId = objectMapper.readTree(resumeCall.getResponse().getContentAsString()).get("id").asText();
        assertThat(resumedId).isEqualTo(consultationId);

        // 4. GET retrieves the previously saved draft data accurately
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Evaluación guardada antes de salir de la consulta"))
                .andExpect(jsonPath("$.anamnesis").value("Detalles clínicos registrados"));

        // Verify only 1 CONSULTATION_STARTED audit was ever written
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_type = 'CONSULTATION' AND action = 'CONSULTATION_STARTED'",
                Integer.class,
                tenant.tenantId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void uninterpretableClinicalDetailsJsonThrowsAndDoesNotSilentlyWipeData() throws Exception {
        TenantFixture tenant = createTenantFixture("eval-corrupt");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-03T10:00:00Z"), Instant.parse("2026-10-03T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();

        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Corrupt clinical_details column with invalid structure (vitalSigns array instead of object)
        jdbc.update("UPDATE consultations SET clinical_details = '{\"vitalSigns\": [1, 2, 3]}'::jsonb WHERE id = ?", consultationId);

        // Reading via GET fails with 500 because parseClinicalDetails throws IllegalStateException
        mvc.perform(get("/api/v1/consultations/{consultationId}", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isInternalServerError());

        // Assert that the raw data in DB was NOT wiped out or overwritten with null/empty
        String rawDetails = jdbc.queryForObject(
                "SELECT clinical_details::text FROM consultations WHERE id = ?",
                String.class,
                consultationId);
        assertThat(rawDetails).contains("[1, 2, 3]");
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
