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
    javax.sql.DataSource dataSource;

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
            jdbc.update("DELETE FROM prescription_items WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM prescriptions WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM diagnoses WHERE tenant_id = ?", tenantId);
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

    // ==========================================
    // HU-022: REGISTRAR DIAGNÓSTICOS
    // ==========================================

    @Test
    void recordMultipleDiagnosesAndEditInDraftSuccessfully() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-ok");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        // Start consultation
        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // 1. Add first diagnosis (PRESUNTIVO)
        MvcResult addResult1 = mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Gastroenteritis aguda presuntiva",
                                    "diagnosisType": "PRESUNTIVO",
                                    "code": "K52.9",
                                    "notes": "Requiere hemograma y coproparasitoscópico"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.consultationId").value(consultationId.toString()))
                .andExpect(jsonPath("$.description").value("Gastroenteritis aguda presuntiva"))
                .andExpect(jsonPath("$.diagnosisType").value("PRESUNTIVO"))
                .andExpect(jsonPath("$.code").value("K52.9"))
                .andExpect(jsonPath("$.notes").value("Requiere hemograma y coproparasitoscópico"))
                .andReturn();

        UUID diag1Id = UUID.fromString(objectMapper.readTree(
                addResult1.getResponse().getContentAsString()).get("id").asText());

        // 2. Add second diagnosis (DEFINITIVO)
        MvcResult addResult2 = mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Deshidratación leve grado I",
                                    "diagnosisType": "DEFINITIVO",
                                    "code": null,
                                    "notes": null
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.description").value("Deshidratación leve grado I"))
                .andExpect(jsonPath("$.diagnosisType").value("DEFINITIVO"))
                .andReturn();

        UUID diag2Id = UUID.fromString(objectMapper.readTree(
                addResult2.getResponse().getContentAsString()).get("id").asText());

        // 3. List diagnoses of consultation (consultationId)
        mvc.perform(get("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(diag1Id.toString()))
                .andExpect(jsonPath("$[0].diagnosisType").value("PRESUNTIVO"))
                .andExpect(jsonPath("$[1].id").value(diag2Id.toString()))
                .andExpect(jsonPath("$[1].diagnosisType").value("DEFINITIVO"));

        // 4. Edit first diagnosis while in BORRADOR
        mvc.perform(put("/api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}", consultationId, diag1Id)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Gastroenteritis parasitaria confirmada",
                                    "diagnosisType": "DEFINITIVO",
                                    "code": "K52.9",
                                    "notes": "Confirmado por coproparasitoscópico"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(diag1Id.toString()))
                .andExpect(jsonPath("$.description").value("Gastroenteritis parasitaria confirmada"))
                .andExpect(jsonPath("$.diagnosisType").value("DEFINITIVO"))
                .andExpect(jsonPath("$.notes").value("Confirmado por coproparasitoscópico"));

        // 5. Query diagnoses from patient history
        mvc.perform(get("/api/v1/patients/{patientId}/diagnoses", patient.patientId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].consultationId").value(consultationId.toString()))
                .andExpect(jsonPath("$[0].diagnosisType").isNotEmpty());

        // 6. Verify audit logs
        verify(auditLogWriter, org.mockito.Mockito.times(2)).append(argThat(record ->
                "DIAGNOSIS_ADDED".equals(record.action())
                        && "CONSULTATION".equals(record.entityType())
                        && consultationId.equals(record.entityId())
                        && tenant.tenantId.equals(record.tenantId())
                        && record.newData() != null
                        && !record.newData().containsKey("description") // No sensitive text in metadata
        ));

        verify(auditLogWriter).append(argThat(record ->
                "DIAGNOSIS_UPDATED".equals(record.action())
                        && "CONSULTATION".equals(record.entityType())
                        && consultationId.equals(record.entityId())
                        && tenant.tenantId.equals(record.tenantId())
                        && record.newData() != null
                        && !record.newData().containsKey("description")
        ));
    }

    @Test
    void fieldValidationRejectsInvalidDiagnoses() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-val");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Blank description
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "   ",
                                    "diagnosisType": "PRESUNTIVO"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));

        // Description > 300 chars
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "%s",
                                    "diagnosisType": "PRESUNTIVO"
                                }
                                """.formatted("A".repeat(301))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("description"));

        // Invalid diagnosisType
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Diagnóstico de prueba",
                                    "diagnosisType": "SOSPECHOSO"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("diagnosisType"));

        // Code > 50 chars
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Diagnóstico de prueba",
                                    "diagnosisType": "DEFINITIVO",
                                    "code": "%s"
                                }
                                """.formatted("C".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));

        // Notes > 2000 chars
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Diagnóstico de prueba",
                                    "diagnosisType": "DEFINITIVO",
                                    "notes": "%s"
                                }
                                """.formatted("N".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("notes"));
    }

    @Test
    void securityAndAuthorizationChecks() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-sec");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID otherVetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dr. Carlos", "Vega", "carlos@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);
        TenantFixture otherVetActor = new TenantFixture(tenant.tenantId, tenant.branchId, otherVetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // 401 unauthenticated
        mvc.perform(get("/api/v1/consultations/{consultationId}/diagnoses", consultationId))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Test\", \"diagnosisType\": \"PRESUNTIVO\"}"))
                .andExpect(status().isUnauthorized());

        // 403 without CONSULTATION_READ on GET
        mvc.perform(get("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "PATIENT_READ"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/patients/{patientId}/diagnoses", patient.patientId)
                        .with(authentication(auth(vetActor, "PATIENT_READ"))))
                .andExpect(status().isForbidden());

        // 403 without CONSULTATION_UPDATE on POST/PUT
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Test\", \"diagnosisType\": \"PRESUNTIVO\"}"))
                .andExpect(status().isForbidden());

        // 403 when different vet attempts to write
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(otherVetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Intento no autorizado\", \"diagnosisType\": \"PRESUNTIVO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void tenantIsolationReturns404WithoutLeakingExistence() throws Exception {
        TenantFixture tenantA = createTenantFixture("diag-iso-a");
        TenantFixture tenantB = createTenantFixture("diag-iso-b");

        UUID vetA = insertUserWithRoleAndBranch(
                tenantA.tenantId, tenantA.branchId, "Dra. Lucia", "Mendez", "lucia@a.test", "VETERINARIO");
        UUID vetB = insertUserWithRoleAndBranch(
                tenantB.tenantId, tenantB.branchId, "Dr. Carlos", "Vega", "carlos@b.test", "VETERINARIO");

        UUID ownerA = insertOwner(tenantA.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patientA = insertPatientWithMedicalRecord(
                tenantA.tenantId, ownerA, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentA = insertAppointment(
                tenantA.tenantId, tenantA.branchId, patientA.patientId, vetA,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);

        TenantFixture vetActorA = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetA);
        TenantFixture vetActorB = new TenantFixture(tenantB.tenantId, tenantB.branchId, vetB);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentA)
                        .with(authentication(auth(vetActorA, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationAId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Vet A creates diagnosis
        MvcResult addResult = mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationAId)
                        .with(authentication(auth(vetActorA, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Diagnóstico A\", \"diagnosisType\": \"DEFINITIVO\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID diagAId = UUID.fromString(objectMapper.readTree(
                addResult.getResponse().getContentAsString()).get("id").asText());

        // Vet B from tenant B attempts to list diagnoses of consultation A -> 404
        mvc.perform(get("/api/v1/consultations/{consultationId}/diagnoses", consultationAId)
                        .with(authentication(auth(vetActorB, "CONSULTATION_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // Vet B attempts to add diagnosis to consultation A -> 404
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationAId)
                        .with(authentication(auth(vetActorB, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Ataque cruzado\", \"diagnosisType\": \"DEFINITIVO\"}"))
                .andExpect(status().isNotFound());

        // Vet B attempts to update diagnosis in consultation A -> 404
        mvc.perform(put("/api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}", consultationAId, diagAId)
                        .with(authentication(auth(vetActorB, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Modificación cruzada\", \"diagnosisType\": \"DEFINITIVO\"}"))
                .andExpect(status().isNotFound());

        // Vet B attempts to list patient A's diagnoses -> 404
        mvc.perform(get("/api/v1/patients/{patientId}/diagnoses", patientA.patientId)
                        .with(authentication(auth(vetActorB, "CONSULTATION_READ"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsWritingWhenConsultationIsNotDraft() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-non-draft");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Add a diagnosis in BORRADOR
        MvcResult addResult = mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Diagnóstico inicial\", \"diagnosisType\": \"PRESUNTIVO\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID diagnosisId = UUID.fromString(objectMapper.readTree(
                addResult.getResponse().getContentAsString()).get("id").asText());

        // Change consultation status to FINALIZADA
        jdbc.update("UPDATE consultations SET status = 'FINALIZADA' WHERE id = ?", consultationId);

        // Attempting to add diagnosis to FINALIZADA consultation -> 409 Conflict
        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Diagnóstico tardío\", \"diagnosisType\": \"DEFINITIVO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONSULTATION_STATUS"));

        // Attempting to update diagnosis in FINALIZADA consultation -> 409 Conflict
        mvc.perform(put("/api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}", consultationId, diagnosisId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Edición tardía\", \"diagnosisType\": \"DEFINITIVO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONSULTATION_STATUS"));
    }

    @Test
    void rollbackWhenAuditFails() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-audit-fail");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Make auditLogWriter throw on append
        doThrow(new RuntimeException("Simulated audit failure"))
                .when(auditLogWriter)
                .append(argThat(rec -> "DIAGNOSIS_ADDED".equals(rec.action())));

        mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Diagnóstico rollback\", \"diagnosisType\": \"PRESUNTIVO\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        // Verify that NO diagnosis was committed to the DB
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM diagnoses WHERE consultation_id = ?",
                Integer.class,
                consultationId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void concurrentDiagnosisWriteSerializedWithConsultationStatusChange() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-conc");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        CountDownLatch t1LockAcquired = new CountDownLatch(1);
        CountDownLatch t2Started = new CountDownLatch(1);
        CountDownLatch t1ProceedToCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Transaction 1: acquires row lock with FOR UPDATE on the consultation
        Future<Void> t1Future = pool.submit(() -> {
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM consultations WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, tenant.tenantId);
                    ps.setObject(2, consultationId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("BORRADOR");
                    }
                }
                t1LockAcquired.countDown();
                assertThat(t1ProceedToCommit.await(5, TimeUnit.SECONDS)).isTrue();

                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "UPDATE consultations SET status = 'FINALIZADA', updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                    ps.setObject(1, tenant.tenantId);
                    ps.setObject(2, consultationId);
                    ps.executeUpdate();
                }
                conn.commit();
            }
            return null;
        });

        assertThat(t1LockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        // Transaction 2: attempts to add diagnosis via REST API (coordinates via lockConsultationForUpdate)
        Future<Integer> t2Future = pool.submit(() -> {
            t2Started.countDown();
            return mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                            .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "description": "Diagnóstico durante carrera",
                                        "diagnosisType": "PRESUNTIVO"
                                    }
                                    """))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });

        assertThat(t2Started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(250);

        // Transaction 2 must be blocked waiting for Transaction 1's lock in PostgreSQL
        assertThat(t2Future.isDone()).isFalse();

        // Transaction 1 commits the status update
        t1ProceedToCommit.countDown();
        t1Future.get(5, TimeUnit.SECONDS);

        // Transaction 2 unblocks, acquires lock, sees non-BORRADOR status, and is rejected with 409 Conflict
        Integer t2Status = t2Future.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(t2Status).isEqualTo(409);

        // Verify NO diagnosis was inserted in the database
        Integer diagCount = jdbc.queryForObject(
                "SELECT count(*) FROM diagnoses WHERE consultation_id = ?",
                Integer.class,
                consultationId);
        assertThat(diagCount).isEqualTo(0);
    }

    @Test
    void concurrentDiagnosisUpdateSerializedWithConsultationStatusChange() throws Exception {
        TenantFixture tenant = createTenantFixture("diag-up-conc");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId, tenant.branchId, "Dra. Lucia", "Mendez", "lucia@petcare.test", "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Bros", "DNI", "77112233");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId, ownerId, "Yoshi", "EXOTICO", "Reptil");
        UUID appointmentId = insertAppointment(
                tenant.tenantId, tenant.branchId, patient.patientId, vetId,
                Instant.parse("2026-10-04T10:00:00Z"), Instant.parse("2026-10-04T10:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", "Control", null);
        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/{appointmentId}/consultation", appointmentId)
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(
                startResult.getResponse().getContentAsString()).get("id").asText());

        // Add a diagnosis in BORRADOR
        MvcResult addResult = mvc.perform(post("/api/v1/consultations/{consultationId}/diagnoses", consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "description": "Diagnóstico inicial",
                                    "diagnosisType": "PRESUNTIVO"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID diagnosisId = UUID.fromString(objectMapper.readTree(
                addResult.getResponse().getContentAsString()).get("id").asText());

        CountDownLatch t1LockAcquired = new CountDownLatch(1);
        CountDownLatch t2Started = new CountDownLatch(1);
        CountDownLatch t1ProceedToCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Transaction 1: locks consultation row and changes status to FINALIZADA
        Future<Void> t1Future = pool.submit(() -> {
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM consultations WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
                    ps.setObject(1, tenant.tenantId);
                    ps.setObject(2, consultationId);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("BORRADOR");
                    }
                }
                t1LockAcquired.countDown();
                assertThat(t1ProceedToCommit.await(5, TimeUnit.SECONDS)).isTrue();

                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "UPDATE consultations SET status = 'FINALIZADA', updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                    ps.setObject(1, tenant.tenantId);
                    ps.setObject(2, consultationId);
                    ps.executeUpdate();
                }
                conn.commit();
            }
            return null;
        });

        assertThat(t1LockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        // Transaction 2: attempts to update diagnosis via REST API
        Future<Integer> t2Future = pool.submit(() -> {
            t2Started.countDown();
            return mvc.perform(put("/api/v1/consultations/{consultationId}/diagnoses/{diagnosisId}", consultationId, diagnosisId)
                            .with(authentication(auth(vetActor, "CONSULTATION_UPDATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "description": "Edición durante carrera concurrente",
                                        "diagnosisType": "DEFINITIVO"
                                    }
                                    """))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        });

        assertThat(t2Started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(250);

        // Transaction 2 must be blocked waiting for Transaction 1's lock in PostgreSQL
        assertThat(t2Future.isDone()).isFalse();

        // Transaction 1 commits the status update
        t1ProceedToCommit.countDown();
        t1Future.get(5, TimeUnit.SECONDS);

        // Transaction 2 unblocks, acquires lock, sees non-BORRADOR status, and is rejected with 409 Conflict
        Integer t2Status = t2Future.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(t2Status).isEqualTo(409);

        // Verify diagnosis was NOT updated in PostgreSQL
        String currentDesc = jdbc.queryForObject(
                "SELECT description FROM diagnoses WHERE id = ?",
                String.class,
                diagnosisId);
        assertThat(currentDesc).isEqualTo("Diagnóstico inicial");
    }

    @Test
    void issuesMultiLinePrescriptionSuccessfullyAndPersistsAtomicallyWithAudit() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-issue");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Valeria",
                "Paredes",
                "valeria@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "César", "Rosas", "DNI", "45678901");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Rocky",
                "CANINO",
                "Beagle");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Alergia cutánea",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String prescriptionPayload = """
                {
                    "generalInstructions": "Administrar con alimento sólido por la mañana.",
                    "idempotencyKey": "idem-rocky-01",
                    "items": [
                        {
                            "medicationName": "Amoxicilina con Ácido Clavulánico 250mg",
                            "dosage": "1 comprimido (250 mg)",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días",
                            "route": "Oral",
                            "instructions": "No interrumpir el antibiótico antes del séptimo día."
                        },
                        {
                            "medicationName": "Prednisolona 5mg",
                            "dosage": "0.5 comprimido (2.5 mg)",
                            "frequency": "Cada 24 horas",
                            "duration": "5 días",
                            "route": "Oral",
                            "instructions": "Reducir gradualmente según evolución."
                        }
                    ]
                }
                """;

        MvcResult emitResult = mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionPayload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.consultationId").value(consultationId.toString()))
                .andExpect(jsonPath("$.prescribedBy").value(vetId.toString()))
                .andExpect(jsonPath("$.veterinarianFullName").value("Dra. Valeria Paredes"))
                .andExpect(jsonPath("$.status").value("EMITIDA"))
                .andExpect(jsonPath("$.generalInstructions").value("Administrar con alimento sólido por la mañana."))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].medicationName").value("Amoxicilina con Ácido Clavulánico 250mg"))
                .andExpect(jsonPath("$.items[0].dosage").value("1 comprimido (250 mg)"))
                .andExpect(jsonPath("$.items[0].frequency").value("Cada 12 horas"))
                .andExpect(jsonPath("$.items[0].duration").value("7 días"))
                .andExpect(jsonPath("$.items[0].route").value("Oral"))
                .andExpect(jsonPath("$.items[1].medicationName").value("Prednisolona 5mg"))
                .andReturn();

        UUID prescriptionId = UUID.fromString(objectMapper.readTree(emitResult.getResponse().getContentAsString()).get("id").asText());
        assertThat(emitResult.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/consultations/" + consultationId + "/prescriptions/" + prescriptionId);

        // Verify retrieval via dedicated endpoint
        mvc.perform(get("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(prescriptionId.toString()))
                .andExpect(jsonPath("$[0].items.length()").value(2))
                .andExpect(jsonPath("$[0].items[0].medicationName").value("Amoxicilina con Ácido Clavulánico 250mg"))
                .andExpect(jsonPath("$[0].items[1].medicationName").value("Prednisolona 5mg"));

        // Verify inclusion in consultation summary
        mvc.perform(get("/api/v1/consultations/" + consultationId)
                        .with(authentication(auth(vetActor, "CONSULTATION_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.prescriptions[0].id").value(prescriptionId.toString()))
                .andExpect(jsonPath("$.prescriptions[0].items.length()").value(2));

        // Verify atomic audit log entry exists without sensitive drug texts
        List<Map<String, Object>> auditEntries = jdbc.queryForList("""
                SELECT action, entity_type, entity_id, new_data
                FROM audit_entries
                WHERE tenant_id = ? AND entity_id = ? AND action = 'PRESCRIPTION_ISSUED'
                """, tenant.tenantId, prescriptionId);

        assertThat(auditEntries).hasSize(1);
        Map<String, Object> audit = auditEntries.get(0);
        assertThat(audit.get("action")).isEqualTo("PRESCRIPTION_ISSUED");
        assertThat(audit.get("entity_type")).isEqualTo("PRESCRIPTION");
        String detailsJson = audit.get("new_data").toString();
        assertThat(detailsJson).contains("\"itemCount\": \"2\"");
        assertThat(detailsJson).contains("\"hasGeneralInstructions\": \"true\"");
        assertThat(detailsJson).doesNotContain("Amoxicilina");
        assertThat(detailsJson).doesNotContain("Prednisolona");
        assertThat(detailsJson).doesNotContain("250 mg");
    }

    @Test
    void rejectsStockDeductionOrInventoryDispensationsExplicitly() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-stock");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Marcos",
                "Ríos",
                "marcos@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Laura", "Vidal", "DNI", "55667788");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Simba",
                "FELINO",
                "Siamés");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Chequeo",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // Attempt with top-level deductStock: true
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "deductStock": true,
                                    "items": [
                                        {
                                            "medicationName": "Meloxicam",
                                            "dosage": "1 ml",
                                            "frequency": "Cada 24 horas",
                                            "duration": "3 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("La dispensación y descuento de inventario no están disponibles")));

        // Attempt with item-level deductStock: true
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Meloxicam",
                                            "dosage": "1 ml",
                                            "frequency": "Cada 24 horas",
                                            "duration": "3 días",
                                            "deductStock": true
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("La dispensación y descuento de inventario no están disponibles")));

        // Attempt with productId provided
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Meloxicam",
                                            "dosage": "1 ml",
                                            "frequency": "Cada 24 horas",
                                            "duration": "3 días",
                                            "productId": "00000000-0000-0000-0000-000000000001"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("La dispensación y descuento de inventario no están disponibles")));
    }

    @Test
    void validatesRequiredAndConstrainedFieldsOnPrescriptionEmission() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-val");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Jorge",
                "Flores",
                "jorge@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Diana", "Castro", "DNI", "66778899");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Pelusa",
                "FELINO",
                "Persa");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Revisión",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // Empty items list
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La prescripción debe incluir al menos un medicamento."));

        // Blank medication name
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "   ",
                                            "dosage": "1 tableta",
                                            "frequency": "Cada 8h",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("El nombre del medicamento es obligatorio."));

        // Medication name exceeding 200 chars
        String longMedName = "A".repeat(201);
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "%s",
                                            "dosage": "1 tableta",
                                            "frequency": "Cada 8h",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """, longMedName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("El nombre del medicamento no debe exceder los 200 caracteres."));

        // Blank dosage
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "   ",
                                            "frequency": "Cada 8h",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La dosis del medicamento es obligatoria."));

        // Blank frequency
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "10 mg",
                                            "frequency": "   ",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La frecuencia del medicamento es obligatoria."));

        // Blank duration
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "10 mg",
                                            "frequency": "Cada 12h",
                                            "duration": "   "
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La duración del tratamiento es obligatoria."));

        // Route exceeding 50 chars
        String longRoute = "R".repeat(51);
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "10 mg",
                                            "frequency": "Cada 12h",
                                            "duration": "5 días",
                                            "route": "%s"
                                        }
                                    ]
                                }
                                """, longRoute)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La vía de administración no debe exceder los 50 caracteres."));

        // Dosage exceeding 100 chars
        String longDosage = "D".repeat(101);
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "%s",
                                            "frequency": "Cada 12h",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """, longDosage)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("La dosis no debe exceder los 100 caracteres."));

        // Instructions exceeding 2000 chars
        String longInstructions = "I".repeat(2001);
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "10 mg",
                                            "frequency": "Cada 12h",
                                            "duration": "5 días",
                                            "instructions": "%s"
                                        }
                                    ]
                                }
                                """, longInstructions)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Las indicaciones del medicamento no deben exceder los 2000 caracteres."));

        // General instructions exceeding 2000 chars
        String longGeneralInstructions = "G".repeat(2001);
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "generalInstructions": "%s",
                                    "items": [
                                        {
                                            "medicationName": "Ibuprofeno veterinario",
                                            "dosage": "10 mg",
                                            "frequency": "Cada 12h",
                                            "duration": "5 días"
                                        }
                                    ]
                                }
                                """, longGeneralInstructions)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Las indicaciones generales no deben exceder los 2000 caracteres."));
    }

    @Test
    void rejectsEmissionWhenConsultationNotInDraftStatus() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-status");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Andrés",
                "Luna",
                "andres@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Pedro", "Navarro", "DNI", "77889900");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Zeus",
                "CANINO",
                "Doberman");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Control",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // Update consultation status to FINALIZADA directly in DB
        jdbc.update("UPDATE consultations SET status = 'FINALIZADA' WHERE id = ?", consultationId);

        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Cefalexina 500mg",
                                            "dosage": "1 tableta",
                                            "frequency": "Cada 12 horas",
                                            "duration": "10 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONSULTATION_STATUS"));
    }

    @Test
    void rejectsEmissionByNonAssignedVeterinarianOrWithoutConsultationUpdateAuthority() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-auth");
        UUID assignedVetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Asignado",
                "Vera",
                "asignado@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID nonAssignedVetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Ajeno",
                "Salas",
                "ajeno@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Mario", "Soto", "DNI", "88990011");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Milo",
                "CANINO",
                "Poodle");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                assignedVetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Vacunación",
                null);

        TenantFixture assignedVetActor = new TenantFixture(tenant.tenantId, tenant.branchId, assignedVetId);
        TenantFixture nonAssignedVetActor = new TenantFixture(tenant.tenantId, tenant.branchId, nonAssignedVetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String validBody = """
                {
                    "items": [
                        {
                            "medicationName": "Doxiciclina 100mg",
                            "dosage": "1 tableta",
                            "frequency": "Cada 24 horas",
                            "duration": "14 días"
                        }
                    ]
                }
                """;

        // Non-assigned veterinarian attempts emission -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(nonAssignedVetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Solo el veterinario asignado a la consulta puede emitir prescripciones."));

        // User without CONSULTATION_UPDATE authority -> 403 ACCESS_DENIED
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Unauthenticated request -> 401 UNAUTHORIZED
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enforcesTenantIsolationOnPrescriptions() throws Exception {
        TenantFixture tenantA = createTenantFixture("presc-t-a");
        TenantFixture tenantB = createTenantFixture("presc-t-b");

        UUID vetA = insertUserWithRoleAndBranch(
                tenantA.tenantId,
                tenantA.branchId,
                "Dr. Alfa",
                "Uno",
                "alfa@" + tenantA.tenantId + ".test",
                "VETERINARIO");
        UUID vetB = insertUserWithRoleAndBranch(
                tenantB.tenantId,
                tenantB.branchId,
                "Dr. Beta",
                "Dos",
                "beta@" + tenantB.tenantId + ".test",
                "VETERINARIO");

        UUID ownerA = insertOwner(tenantA.tenantId, "Ana", "López", "DNI", "11223344");
        PatientWithMedicalRecord patientA = insertPatientWithMedicalRecord(
                tenantA.tenantId,
                ownerA,
                "Max",
                "CANINO",
                "Labrador");
        Instant now = Instant.now();
        UUID apptA = insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                patientA.patientId,
                vetA,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Chequeo",
                null);

        TenantFixture vetActorA = new TenantFixture(tenantA.tenantId, tenantA.branchId, vetA);
        TenantFixture vetActorB = new TenantFixture(tenantB.tenantId, tenantB.branchId, vetB);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptA + "/consultation")
                        .with(authentication(auth(vetActorA, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationIdA = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String validBody = """
                {
                    "items": [
                        {
                            "medicationName": "Meloxicam 2mg",
                            "dosage": "1 ml",
                            "frequency": "Cada 24 horas",
                            "duration": "3 días"
                        }
                    ]
                }
                """;

        // Tenant B attempts to issue prescription on Tenant A's consultation -> 404 RESOURCE_NOT_FOUND
        mvc.perform(post("/api/v1/consultations/" + consultationIdA + "/prescriptions")
                        .with(authentication(auth(vetActorB, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // Tenant B attempts to read prescriptions from Tenant A's consultation -> 404 RESOURCE_NOT_FOUND
        mvc.perform(get("/api/v1/consultations/" + consultationIdA + "/prescriptions")
                        .with(authentication(auth(vetActorB, "CONSULTATION_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void idempotentRetryReturnsExistingPrescriptionWithoutDuplicates() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-idem");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Camila",
                "Duarte",
                "camila@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Ernesto", "Silva", "DNI", "22334455");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Kira",
                "CANINO",
                "Husky");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Tos de las perreras",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String initialRequest = """
                {
                    "idempotencyKey": "retry-key-001",
                    "generalInstructions": "Reposo e hidratación constante.",
                    "items": [
                        {
                            "medicationName": "Jarabe antitusígeno",
                            "dosage": "5 ml",
                            "frequency": "Cada 8 horas",
                            "duration": "5 días"
                        }
                    ]
                }
                """;

        // First emission
        MvcResult firstResult = mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialRequest))
                .andExpect(status().isCreated())
                .andReturn();

        UUID firstPrescriptionId = UUID.fromString(objectMapper.readTree(firstResult.getResponse().getContentAsString()).get("id").asText());

        // Retry of identical emission with same idempotencyKey
        MvcResult retryResult = mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialRequest))
                .andExpect(status().isCreated())
                .andReturn();

        UUID retryPrescriptionId = UUID.fromString(objectMapper.readTree(retryResult.getResponse().getContentAsString()).get("id").asText());
        assertThat(retryPrescriptionId).isEqualTo(firstPrescriptionId);

        // Verify only 1 prescription and 1 audit record exist for this key in the database
        Integer countInDb = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ? AND idempotency_key = 'retry-key-001'",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(countInDb).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'PRESCRIPTION_ISSUED'",
                Integer.class,
                tenant.tenantId,
                firstPrescriptionId);
        assertThat(auditCount).isEqualTo(1);

        // An intentional additional prescription with a different key is permitted
        String secondRequest = """
                {
                    "idempotencyKey": "retry-key-002",
                    "generalInstructions": "Aplicación tópica.",
                    "items": [
                        {
                            "medicationName": "Pomada cicatrizante",
                            "dosage": "Capa fina",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días"
                        }
                    ]
                }
                """;

        MvcResult secondResult = mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRequest))
                .andExpect(status().isCreated())
                .andReturn();

        UUID secondPrescriptionId = UUID.fromString(objectMapper.readTree(secondResult.getResponse().getContentAsString()).get("id").asText());
        assertThat(secondPrescriptionId).isNotEqualTo(firstPrescriptionId);

        // Total prescriptions for this consultation is now 2
        Integer totalInDb = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ?",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(totalInDb).isEqualTo(2);
    }

    @Test
    void rollsBackTransactionIfAuditFailsOnPrescriptionEmission() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-rollback");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Samuel",
                "Peña",
                "samuel@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Julia", "Reyes", "DNI", "33445566");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Oreo",
                "FELINO",
                "Criollo");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Evaluación",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        // Make auditLogWriter throw on PRESCRIPTION_ISSUED
        doThrow(new RuntimeException("Simulated audit disk failure"))
                .when(auditLogWriter)
                .append(argThat(rec -> rec != null && "PRESCRIPTION_ISSUED".equals(rec.action())));

        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "items": [
                                        {
                                            "medicationName": "Antiparasitario interno",
                                            "dosage": "1 tableta",
                                            "frequency": "Dosis única",
                                            "duration": "1 día"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isInternalServerError());

        // Verify that neither prescription nor prescription_items were committed to DB
        Integer prescCount = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ?",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(prescCount).isZero();

        Integer itemCount = jdbc.queryForObject(
                "SELECT count(*) FROM prescription_items WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId);
        assertThat(itemCount).isZero();
    }

    @Test
    void rejectsPrescriptionIssuanceAndRetryByNonAssignedVeterinarian() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-non-assigned");
        UUID assignedVetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Camila",
                "Torres",
                "camila@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID otherVetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Roberto",
                "Gómez",
                "roberto@" + tenant.tenantId + ".test",
                "VETERINARIO");

        UUID ownerId = insertOwner(tenant.tenantId, "Patricia", "Sánchez", "DNI", "88776655");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Toby",
                "CANINO",
                "Beagle");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                assignedVetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Revisión dental",
                null);

        TenantFixture assignedVetActor = new TenantFixture(tenant.tenantId, tenant.branchId, assignedVetId);
        TenantFixture otherVetActor = new TenantFixture(tenant.tenantId, tenant.branchId, otherVetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String initialRequest = """
                {
                    "idempotencyKey": "vet-auth-key-01",
                    "items": [
                        {
                            "medicationName": "Clorhexidina gel dental",
                            "dosage": "1 aplicación",
                            "frequency": "Cada 12 horas",
                            "duration": "10 días"
                        }
                    ]
                }
                """;

        // Assigned vet issues prescription successfully
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(assignedVetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialRequest))
                .andExpect(status().isCreated());

        // Other vet attempts to retrieve or re-issue with the same key -> MUST BE FORBIDDEN (403)
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(otherVetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Other vet attempts to issue a new prescription with new key -> MUST BE FORBIDDEN (403)
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(otherVetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "idempotencyKey": "vet-auth-key-02",
                                    "items": [
                                        {
                                            "medicationName": "Otro medicamento",
                                            "dosage": "1 dosis",
                                            "frequency": "Cada 24 horas",
                                            "duration": "3 días"
                                        }
                                    ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentContentReturningConflict() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-diff-content");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Alonso",
                "Navarro",
                "alonso@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Ernesto", "Salas", "DNI", "55443322");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Milo",
                "FELINO",
                "Siamés");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Otitis",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String originalRequest = """
                {
                    "idempotencyKey": "same-key-diff-content",
                    "generalInstructions": "Limpiar oído antes de aplicar.",
                    "items": [
                        {
                            "medicationName": "Gotas óticas antibióticas",
                            "dosage": "3 gotas",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días"
                        }
                    ]
                }
                """;

        // Initial emission
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(originalRequest))
                .andExpect(status().isCreated());

        // Same key, different dosage (3 gotas -> 5 gotas)
        String diffDosageRequest = """
                {
                    "idempotencyKey": "same-key-diff-content",
                    "generalInstructions": "Limpiar oído antes de aplicar.",
                    "items": [
                        {
                            "medicationName": "Gotas óticas antibióticas",
                            "dosage": "5 gotas",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días"
                        }
                    ]
                }
                """;
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diffDosageRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Same key, different general instructions
        String diffInstructionsRequest = """
                {
                    "idempotencyKey": "same-key-diff-content",
                    "generalInstructions": "Instrucción completamente diferente.",
                    "items": [
                        {
                            "medicationName": "Gotas óticas antibióticas",
                            "dosage": "3 gotas",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días"
                        }
                    ]
                }
                """;
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diffInstructionsRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Same key, extra line
        String diffLinesRequest = """
                {
                    "idempotencyKey": "same-key-diff-content",
                    "generalInstructions": "Limpiar oído antes de aplicar.",
                    "items": [
                        {
                            "medicationName": "Gotas óticas antibióticas",
                            "dosage": "3 gotas",
                            "frequency": "Cada 12 horas",
                            "duration": "7 días"
                        },
                        {
                            "medicationName": "Analgésico oral",
                            "dosage": "1 tableta",
                            "frequency": "Cada 24 horas",
                            "duration": "3 días"
                        }
                    ]
                }
                """;
        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diffLinesRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Verify only 1 prescription in DB and 1 audit record
        Integer countInDb = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ?",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(countInDb).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND action = 'PRESCRIPTION_ISSUED'",
                Integer.class,
                tenant.tenantId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void concurrentEmissionWithSameKeyAndSameContentReturnsSamePrescription() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-concurrent");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Mariano",
                "Castro",
                "mariano@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Gladys", "Paredes", "DNI", "11223344");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Luna",
                "CANINO",
                "Poodle");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Dermatitis",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String concurrentRequest = """
                {
                    "idempotencyKey": "concurrent-key-999",
                    "generalInstructions": "Baños medicados 2 veces por semana.",
                    "items": [
                        {
                            "medicationName": "Champú antiséptico",
                            "dosage": "1 aplicación tópica",
                            "frequency": "Cada 72 horas",
                            "duration": "14 días"
                        }
                    ]
                }
                """;

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<MvcResult> emitTask = () -> mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(concurrentRequest))
                .andExpect(status().isCreated())
                .andReturn();

        java.util.concurrent.Future<MvcResult> f1 = executor.submit(emitTask);
        java.util.concurrent.Future<MvcResult> f2 = executor.submit(emitTask);

        MvcResult r1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
        MvcResult r2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        UUID id1 = UUID.fromString(objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asText());
        UUID id2 = UUID.fromString(objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asText());
        assertThat(id1).isEqualTo(id2);

        Integer countInDb = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ?",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(countInDb).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND action = 'PRESCRIPTION_ISSUED'",
                Integer.class,
                tenant.tenantId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void rejectsPrescriptionWithNullItemLineUniformly() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-null-item");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dr. Felipe",
                "Arce",
                "felipe@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Carla", "Vargas", "DNI", "66778899");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Boby",
                "CANINO",
                "Labrador");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Control",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items[0]"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_VALUE"));
    }

    @Test
    void rejectsLongIdempotencyKeyWithoutSilentlyTruncating() throws Exception {
        TenantFixture tenant = createTenantFixture("presc-long-key");
        UUID vetId = insertUserWithRoleAndBranch(
                tenant.tenantId,
                tenant.branchId,
                "Dra. Elena",
                "Ríos",
                "elena@" + tenant.tenantId + ".test",
                "VETERINARIO");
        UUID ownerId = insertOwner(tenant.tenantId, "Lucas", "Mendoza", "DNI", "77889900");
        PatientWithMedicalRecord patient = insertPatientWithMedicalRecord(
                tenant.tenantId,
                ownerId,
                "Simba",
                "FELINO",
                "Persa");
        Instant now = Instant.now();
        UUID apptId = insertAppointment(
                tenant.tenantId,
                tenant.branchId,
                patient.patientId,
                vetId,
                now.minusSeconds(1800),
                now.plusSeconds(1800),
                "CONSULTA_GENERAL",
                "EN_ESPERA",
                "Control",
                null);

        TenantFixture vetActor = new TenantFixture(tenant.tenantId, tenant.branchId, vetId);

        MvcResult startResult = mvc.perform(post("/api/v1/appointments/" + apptId + "/consultation")
                        .with(authentication(auth(vetActor, "CONSULTATION_CREATE", "CONSULTATION_READ", "CONSULTATION_UPDATE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID consultationId = UUID.fromString(objectMapper.readTree(startResult.getResponse().getContentAsString()).get("id").asText());

        String longKey = "k".repeat(101);
        String request = """
                {
                    "idempotencyKey": "%s",
                    "items": [
                        {
                            "medicationName": "Antiinflamatorio",
                            "dosage": "1 tableta",
                            "frequency": "Cada 24 horas",
                            "duration": "3 días"
                        }
                    ]
                }
                """.formatted(longKey);

        mvc.perform(post("/api/v1/consultations/" + consultationId + "/prescriptions")
                        .with(authentication(auth(vetActor, "CONSULTATION_UPDATE", "CONSULTATION_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idempotencyKey"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_VALUE"));

        // Verify no prescription was created
        Integer countInDb = jdbc.queryForObject(
                "SELECT count(*) FROM prescriptions WHERE tenant_id = ? AND consultation_id = ?",
                Integer.class,
                tenant.tenantId,
                consultationId);
        assertThat(countInDb).isZero();
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
