package com.petcare.enterprise.appointments.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository;
import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository.AppointmentStatusHistoryRecord;
import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import com.petcare.enterprise.appointments.infrastructure.events.AppointmentCreatedEventListener;
import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AppointmentControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AppointmentCreatedEventListener eventListener;

    @MockitoSpyBean
    AuditLogWriter auditLogWriter;

    @MockitoSpyBean
    AppointmentScheduleRepository scheduleRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @AfterEach
    void cleanUpTenants() {
        reset(auditLogWriter, eventListener, scheduleRepository);
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
            jdbc.update("DELETE FROM appointments WHERE tenant_id = ?", tenantId);
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
    void searchesScheduleByDateBranchAndVeterinarianWithChronologicalOrderAndPagination() throws Exception {
        TenantFixture tenant = createTenantFixture("agenda-a");
        UUID branchCentral = insertBranch(tenant.tenantId, "Sede Central Miraflores", "America/Lima", true);
        UUID branchNorte = insertBranch(tenant.tenantId, "Sede Los Olivos", "America/Lima", true);

        UUID vetAna = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana@petcare.test", "VETERINARIO", "ACTIVO");
        UUID vetCarlos = insertUserWithRole(tenant.tenantId, "Carlos", "Vega", "carlos@petcare.test", "VETERINARIO", "ACTIVO");

        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "44556677");
        UUID patientLuna = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Golden Retriever");
        UUID patientMilo = insertPatient(tenant.tenantId, owner, "Milo", "GATO", "Siames");

        // 2026-09-27 in America/Lima (UTC-5):
        // 09:00 Lima = 14:00Z, 11:00 Lima = 16:00Z, 15:30 Lima = 20:30Z
        UUID apptEarly = insertAppointment(
                tenant.tenantId,
                branchCentral,
                patientLuna,
                vetAna,
                Instant.parse("2026-09-27T14:00:00Z"),
                Instant.parse("2026-09-27T14:30:00Z"),
                "CONSULTA_GENERAL",
                "CONFIRMADA",
                "Control anual",
                "Traer carnet interno");

        UUID apptMid = insertAppointment(
                tenant.tenantId,
                branchCentral,
                patientMilo,
                vetCarlos,
                Instant.parse("2026-09-27T16:00:00Z"),
                Instant.parse("2026-09-27T16:45:00Z"),
                "VACUNACION",
                "PROGRAMADA",
                "Refuerzo anual",
                null);

        UUID apptNorth = insertAppointment(
                tenant.tenantId,
                branchNorte,
                patientLuna,
                vetAna,
                Instant.parse("2026-09-27T20:30:00Z"),
                Instant.parse("2026-09-27T21:00:00Z"),
                "CONTROL",
                "EN_ESPERA",
                "Revisión postoperatoria",
                "Nota privada interna");

        String responseJson = mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("page", "0")
                        .param("size", "10")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(apptEarly.toString()))
                .andExpect(jsonPath("$.content[0].branchId").value(branchCentral.toString()))
                .andExpect(jsonPath("$.content[0].branchName").value("Sede Central Miraflores"))
                .andExpect(jsonPath("$.content[0].patientId").value(patientLuna.toString()))
                .andExpect(jsonPath("$.content[0].patientName").value("Luna"))
                .andExpect(jsonPath("$.content[0].veterinarianId").value(vetAna.toString()))
                .andExpect(jsonPath("$.content[0].veterinarianFullName").value("Ana Salazar"))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.content[0].appointmentType").value("CONSULTA_GENERAL"))
                .andExpect(jsonPath("$.content[0].startsAt").value("2026-09-27T14:00:00Z"))
                .andExpect(jsonPath("$.content[0].endsAt").value("2026-09-27T14:30:00Z"))
                .andExpect(jsonPath("$.content[1].id").value(apptMid.toString()))
                .andExpect(jsonPath("$.content[2].id").value(apptNorth.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseJson)
                .doesNotContain("tenantId")
                .doesNotContain("notes")
                .doesNotContain("reason")
                .doesNotContain("Traer carnet interno")
                .doesNotContain("Nota privada interna")
                .doesNotContain("createdBy")
                .doesNotContain("passwordHash");

        // Filter by branchId
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("branchId", branchCentral.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(apptEarly.toString()))
                .andExpect(jsonPath("$.content[1].id").value(apptMid.toString()));

        // Filter by veterinarianId and descending order
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("veterinarianId", vetAna.toString())
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(apptNorth.toString()))
                .andExpect(jsonPath("$.content[1].id").value(apptEarly.toString()));
    }

    @Test
    void handlesBranchTimezoneBoundariesWithoutMixingAdjacentDays() throws Exception {
        TenantFixture tenant = createTenantFixture("agenda-tz");
        UUID branchLima = insertBranch(tenant.tenantId, "Sede Lima", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Marco", "Rios", "marco@petcare.test", "VETERINARIO", "ACTIVO");
        UUID owner = insertOwner(tenant.tenantId, "Elena", "Castro", "DNI", "88776655");
        UUID patient = insertPatient(tenant.tenantId, owner, "Rocky", "PERRO", "Bulldog");

        // 2026-09-28T03:30:00Z is 2026-09-27 22:30:00 in America/Lima (UTC-5)
        UUID lateNightLima = insertAppointment(
                tenant.tenantId,
                branchLima,
                patient,
                vet,
                Instant.parse("2026-09-28T03:30:00Z"),
                Instant.parse("2026-09-28T04:00:00Z"),
                "URGENCIA",
                "EN_ATENCION",
                "Atención nocturna",
                null);

        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(lateNightLima.toString()));

        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void isolatesScheduleAndOptionsBetweenTenants() throws Exception {
        TenantFixture tenantA = createTenantFixture("agenda-iso-a");
        TenantFixture tenantB = createTenantFixture("agenda-iso-b");

        UUID branchA = insertBranch(tenantA.tenantId, "Sede A", "America/Lima", true);
        UUID vetA = insertUserWithRole(tenantA.tenantId, "Vet", "Alfa", "vet.a@petcare.test", "VETERINARIO", "ACTIVO");
        UUID ownerA = insertOwner(tenantA.tenantId, "Owner", "Alfa", "DNI", "11112222");
        UUID patientA = insertPatient(tenantA.tenantId, ownerA, "Max", "PERRO", "Labrador");

        UUID branchB = insertBranch(tenantB.tenantId, "Sede B", "America/Lima", true);
        UUID vetB = insertUserWithRole(tenantB.tenantId, "Vet", "Beta", "vet.b@petcare.test", "VETERINARIO", "ACTIVO");
        UUID ownerB = insertOwner(tenantB.tenantId, "Owner", "Beta", "DNI", "33334444");
        UUID patientB = insertPatient(tenantB.tenantId, ownerB, "Nina", "GATO", "Persa");

        UUID apptA = insertAppointment(
                tenantA.tenantId,
                branchA,
                patientA,
                vetA,
                Instant.parse("2026-09-27T15:00:00Z"),
                Instant.parse("2026-09-27T15:30:00Z"),
                "CONSULTA_GENERAL",
                "PROGRAMADA",
                "Cita A",
                null);

        insertAppointment(
                tenantB.tenantId,
                branchB,
                patientB,
                vetB,
                Instant.parse("2026-09-27T15:00:00Z"),
                Instant.parse("2026-09-27T15:30:00Z"),
                "CONSULTA_GENERAL",
                "PROGRAMADA",
                "Cita B",
                null);

        // Tenant A only sees apptA
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(apptA.toString()));

        // Filtering by Tenant B's branchId or veterinarianId from Tenant A returns empty results without leaking
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("branchId", branchB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("veterinarianId", vetB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void listsOnlyActiveTenantBranchesAndActiveTenantVeterinariansInOptions() throws Exception {
        TenantFixture tenant = createTenantFixture("agenda-opts");
        UUID activeBranch = insertBranch(tenant.tenantId, "Sede Surco", "America/Lima", true);
        insertBranch(tenant.tenantId, "Sede Antigua Inactiva", "America/Lima", false);

        UUID activeVet = insertUserWithRole(tenant.tenantId, "Valeria", "Gomez", "valeria@petcare.test", "VETERINARIO", "ACTIVO");
        insertUserWithRole(tenant.tenantId, "Inactivo", "Vet", "inactivo@petcare.test", "VETERINARIO", "INACTIVO");
        insertUserWithRole(tenant.tenantId, "Recepcion", "User", "recepcion@petcare.test", "RECEPCIONISTA", "ACTIVO");

        String optionsJson = mvc.perform(get("/api/v1/appointments/options")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branches[?(@.id == '" + activeBranch + "')].name").value("Sede Surco"))
                .andExpect(jsonPath("$.veterinarians.length()").value(1))
                .andExpect(jsonPath("$.veterinarians[0].id").value(activeVet.toString()))
                .andExpect(jsonPath("$.veterinarians[0].fullName").value("Valeria Gomez"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(optionsJson)
                .doesNotContain("Sede Antigua Inactiva")
                .doesNotContain("Inactivo Vet")
                .doesNotContain("Recepcion User")
                .doesNotContain("valeria@petcare.test")
                .doesNotContain("passwordHash");
    }

    @Test
    void enforcesAuthenticationAuthorizationAndQueryValidation() throws Exception {
        TenantFixture tenant = createTenantFixture("agenda-sec");

        mvc.perform(get("/api/v1/appointments").param("date", "2026-09-27"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/appointments/options"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "PATIENT_READ")))
                        .param("date", "2026-09-27"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/appointments/options")
                        .with(authentication(auth(tenant, "PATIENT_READ"))))
                .andExpect(status().isForbidden());

        // Missing date
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Invalid calendar date
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Invalid branchId UUID
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("branchId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Invalid veterinarianId UUID
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("veterinarianId", "invalid-vet"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Invalid page size
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Invalid sort direction
        mvc.perform(get("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .param("date", "2026-09-27")
                        .param("direction", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void enforcesDatabaseConstraintsForTimeRangeStatusAndTenantForeignKeys() {
        TenantFixture tenantA = createTenantFixture("agenda-db-a");
        TenantFixture tenantB = createTenantFixture("agenda-db-b");

        UUID vetA = insertUserWithRole(tenantA.tenantId, "Vet", "A", "veta@db.test", "VETERINARIO", "ACTIVO");
        UUID ownerA = insertOwner(tenantA.tenantId, "Owner", "A", "DNI", "90001111");
        UUID patientA = insertPatient(tenantA.tenantId, ownerA, "Toby", "PERRO", "Beagle");

        UUID branchB = insertBranch(tenantB.tenantId, "Branch B", "America/Lima", true);

        // ends_at <= starts_at must fail
        assertThatThrownBy(() -> insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                patientA,
                vetA,
                Instant.parse("2026-09-27T15:00:00Z"),
                Instant.parse("2026-09-27T15:00:00Z"),
                "CONSULTA",
                "PROGRAMADA",
                null,
                null)).isInstanceOf(DataIntegrityViolationException.class);

        // Invalid status must fail
        assertThatThrownBy(() -> insertAppointment(
                tenantA.tenantId,
                tenantA.branchId,
                patientA,
                vetA,
                Instant.parse("2026-09-27T15:00:00Z"),
                Instant.parse("2026-09-27T15:30:00Z"),
                "CONSULTA",
                "ESTADO_INVENTADO",
                null,
                null)).isInstanceOf(DataIntegrityViolationException.class);

        // Cross-tenant branch must fail composite FK
        assertThatThrownBy(() -> insertAppointment(
                tenantA.tenantId,
                branchB,
                patientA,
                vetA,
                Instant.parse("2026-09-27T15:00:00Z"),
                Instant.parse("2026-09-27T15:30:00Z"),
                "CONSULTA",
                "PROGRAMADA",
                null,
                null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsAppointmentWithProgramadaStatusAuditEntryAndAfterCommitEvent() throws Exception {
        TenantFixture tenant = createTenantFixture("create-ok");
        UUID branchCentral = insertBranch(tenant.tenantId, "Sede Miraflores", "America/Lima", true);
        UUID vetAna = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-create@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vetAna, branchCentral);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "70001122");
        UUID patientLuna = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Golden Retriever");

        reset(eventListener);

        String payload = """
                {
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-28T09:00",
                  "endsAtLocal": "2026-09-28T09:30",
                  "appointmentType": "CONSULTA_GENERAL",
                  "reason": "  Chequeo anual preventivo  ",
                  "notes": "  Paciente tranquilo  "
                }
                """.formatted(branchCentral, patientLuna, vetAna);

        String expectedCorrelationId = "11111111-2222-4333-8444-555555555555";
        var mvcResult = mvc.perform(post("/api/v1/appointments")
                        .header("X-Correlation-ID", expectedCorrelationId)
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/appointments/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.branchId").value(branchCentral.toString()))
                .andExpect(jsonPath("$.branchName").value("Sede Miraflores"))
                .andExpect(jsonPath("$.startsAt").value("2026-09-28T14:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2026-09-28T14:30:00Z"))
                .andExpect(jsonPath("$.status").value("PROGRAMADA"))
                .andExpect(jsonPath("$.appointmentType").value("CONSULTA_GENERAL"))
                .andExpect(jsonPath("$.patientId").value(patientLuna.toString()))
                .andExpect(jsonPath("$.patientName").value("Luna"))
                .andExpect(jsonPath("$.veterinarianId").value(vetAna.toString()))
                .andExpect(jsonPath("$.veterinarianFullName").value("Ana Salazar"))
                .andReturn();

        String responseJson = mvcResult.getResponse().getContentAsString();
        String responseCorrelationId = mvcResult.getResponse().getHeader("X-Correlation-ID");
        assertThat(responseCorrelationId).isNotBlank();

        String locationHeader = mvcResult.getResponse().getHeader("Location");
        assertThat(locationHeader).isNotNull();
        UUID createdAppointmentId = UUID.fromString(
                locationHeader.substring("/api/v1/appointments/".length()));

        assertThat(responseJson).doesNotContain("tenantId", "createdBy", "passwordHash");

        Integer auditCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM audit_entries
                WHERE tenant_id = ?
                  AND actor_user_id = ?
                  AND entity_id = ?
                  AND action = 'APPOINTMENT_CREATED'
                  AND entity_type = 'APPOINTMENT'
                """, Integer.class, tenant.tenantId, tenant.userId, createdAppointmentId);
        assertThat(auditCount).isEqualTo(1);

        String auditCorrelationId = jdbc.queryForObject("""
                SELECT correlation_id
                FROM audit_entries
                WHERE tenant_id = ?
                  AND entity_id = ?
                  AND action = 'APPOINTMENT_CREATED'
                """, String.class, tenant.tenantId, createdAppointmentId);
        assertThat(auditCorrelationId).isEqualTo(responseCorrelationId);

        ArgumentCaptor<AppointmentCreated> eventCaptor = ArgumentCaptor.forClass(AppointmentCreated.class);
        verify(eventListener, times(1)).onAppointmentCreated(eventCaptor.capture());

        AppointmentCreated evt = eventCaptor.getValue();
        assertThat(evt.tenantId()).isEqualTo(tenant.tenantId);
        assertThat(evt.appointmentId()).isEqualTo(createdAppointmentId);
        assertThat(evt.branchId()).isEqualTo(branchCentral);
        assertThat(evt.patientId()).isEqualTo(patientLuna);
        assertThat(evt.veterinarianId()).isEqualTo(vetAna);
        assertThat(evt.createdBy()).isEqualTo(tenant.userId);
        assertThat(evt.startsAt()).isEqualTo(Instant.parse("2026-09-28T14:00:00Z"));
        assertThat(evt.endsAt()).isEqualTo(Instant.parse("2026-09-28T14:30:00Z"));
        assertThat(evt.type()).isEqualTo("CONSULTA_GENERAL");
        assertThat(evt.status()).isEqualTo("PROGRAMADA");
        assertThat(evt.correlationId()).isEqualTo(UUID.fromString(expectedCorrelationId));
    }

    @Test
    void rejectsUnauthenticatedForbiddenAndInvalidAppointmentCreationPayloads() throws Exception {
        TenantFixture tenant = createTenantFixture("create-sec");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-sec@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "70002233");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        String validPayload = appointmentPayload(branch, patient, vet, "09:00", "09:30");

        // 401 without authentication
        mvc.perform(post("/api/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 403 with APPOINTMENT_READ only (e.g. VETERINARIO)
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 400 when unknown fields (tenantId, status, createdBy) are supplied
        String forbiddenFieldPayload = """
                {
                  "tenantId": "%s",
                  "status": "CONFIRMADA",
                  "createdBy": "%s",
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-28T09:00",
                  "endsAtLocal": "2026-09-28T09:30",
                  "appointmentType": "CONSULTA_GENERAL"
                }
                """.formatted(tenant.tenantId, tenant.userId, branch, patient, vet);

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forbiddenFieldPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        // 400 when endsAtLocal <= startsAtLocal
        String invertedTimePayload = appointmentPayload(branch, patient, vet, "10:00", "09:30");

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invertedTimePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("endsAtLocal"));

        // 400 when appointmentType is outside the closed catalog
        String invalidTypePayload = """
                {
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-28T09:00",
                  "endsAtLocal": "2026-09-28T09:30",
                  "appointmentType": "PELUQUERIA_SPA"
                }
                """.formatted(branch, patient, vet);

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidTypePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("appointmentType"));
    }

    @Test
    void rejectsDstGapAndDstOverlapInBranchTimeZone() throws Exception {
        TenantFixture tenant = createTenantFixture("create-dst");
        UUID branchNy = insertBranch(tenant.tenantId, "Sede New York", "America/New_York", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-dst@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branchNy);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "70003344");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        // Spring-forward gap in America/New_York: 2026-03-08 02:15 does not exist
        String dstGapPayload = """
                {
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-03-08T02:15",
                  "endsAtLocal": "2026-03-08T02:45",
                  "appointmentType": "CONSULTA_GENERAL"
                }
                """.formatted(branchNy, patient, vet);

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dstGapPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startsAtLocal"));

        // Fall-back overlap in America/New_York: 2026-11-01 01:15 occurs twice
        String dstOverlapPayload = """
                {
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-11-01T01:15",
                  "endsAtLocal": "2026-11-01T01:45",
                  "appointmentType": "CONSULTA_GENERAL"
                }
                """.formatted(branchNy, patient, vet);

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dstOverlapPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("startsAtLocal"));
    }

    @Test
    void returnsUniform404ForMissingInactiveForeignResourcesAnd400ForUnassignedOrNonVetSameTenantUser() throws Exception {
        TenantFixture tenantA = createTenantFixture("create-res-a");
        TenantFixture tenantB = createTenantFixture("create-res-b");

        UUID branchActive = insertBranch(tenantA.tenantId, "Sede Activa", "America/Lima", true);
        UUID branchOtherActive = insertBranch(tenantA.tenantId, "Sede Secundaria", "America/Lima", true);
        UUID branchInactive = insertBranch(tenantA.tenantId, "Sede Inactiva", "America/Lima", false);
        UUID branchForeign = insertBranch(tenantB.tenantId, "Sede Ajena", "America/Lima", true);

        UUID vetAssigned = insertUserWithRole(tenantA.tenantId, "Ana", "Salazar", "ana-a@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenantA.tenantId, vetAssigned, branchActive);

        UUID vetUnassigned = insertUserWithRole(tenantA.tenantId, "Pedro", "Diaz", "pedro-a@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenantA.tenantId, vetUnassigned, branchOtherActive);

        UUID vetWithoutAnyBranch = insertUserWithRole(tenantA.tenantId, "Luis", "Torres", "luis-a@petcare.test", "VETERINARIO", "ACTIVO");

        UUID vetInactive = insertUserWithRole(tenantA.tenantId, "Rosa", "Mejia", "rosa-a@petcare.test", "VETERINARIO", "INACTIVO");
        assignUserToBranch(tenantA.tenantId, vetInactive, branchActive);

        UUID recepUser = insertUserWithRole(tenantA.tenantId, "Clara", "Rios", "clara-a@petcare.test", "RECEPCIONISTA", "ACTIVO");
        assignUserToBranch(tenantA.tenantId, recepUser, branchActive);

        UUID vetForeign = insertUserWithRole(tenantB.tenantId, "Marco", "Polo", "marco-b@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenantB.tenantId, vetForeign, branchForeign);

        UUID ownerActive = insertOwner(tenantA.tenantId, "Lucia", "Paredes", "DNI", "71112222");
        UUID patientActive = insertPatient(tenantA.tenantId, ownerActive, "Luna", "PERRO", "Mestizo");

        UUID patientInactive = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients (id, tenant_id, owner_id, name, species, breed, sex, status)
                VALUES (?, ?, ?, 'Toby', 'PERRO', 'Mestizo', 'MACHO', 'INACTIVO')
                """, patientInactive, tenantA.tenantId, ownerActive);

        UUID ownerInactive = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO owners (id, tenant_id, first_name, last_name, document_type, document_number, phone, active)
                VALUES (?, ?, 'Inactivo', 'Owner', 'DNI', '72223333', '999888777', false)
                """, ownerInactive, tenantA.tenantId);
        UUID patientWithInactiveOwner = insertPatient(tenantA.tenantId, ownerInactive, "Max", "PERRO", "Mestizo");

        UUID ownerForeign = insertOwner(tenantB.tenantId, "Ajeno", "Owner", "DNI", "73334444");
        UUID patientForeign = insertPatient(tenantB.tenantId, ownerForeign, "Rex", "PERRO", "Mestizo");

        List<String> payloadsExpecting404 = List.of(
                // Inactive branch
                appointmentPayload(branchInactive, patientActive, vetAssigned, "09:00", "09:30"),
                // Foreign branch
                appointmentPayload(branchForeign, patientActive, vetAssigned, "09:00", "09:30"),
                // Inactive patient
                appointmentPayload(branchActive, patientInactive, vetAssigned, "09:00", "09:30"),
                // Patient with inactive owner
                appointmentPayload(branchActive, patientWithInactiveOwner, vetAssigned, "09:00", "09:30"),
                // Foreign patient
                appointmentPayload(branchActive, patientForeign, vetAssigned, "09:00", "09:30"),
                // Inactive veterinarian
                appointmentPayload(branchActive, patientActive, vetInactive, "09:00", "09:30"),
                // Foreign veterinarian
                appointmentPayload(branchActive, patientActive, vetForeign, "09:00", "09:30"));

        for (String payload : payloadsExpecting404) {
            mvc.perform(post("/api/v1/appointments")
                            .with(authentication(auth(tenantA, "APPOINTMENT_CREATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value(
                            "El recurso solicitado para la cita no fue encontrado o no está disponible."));
        }

        // Veterinarian assigned to another branch -> 400 VALIDATION_FAILED on veterinarianId
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchActive, patientActive, vetUnassigned, "09:00", "09:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("veterinarianId"));

        // Veterinarian without any row in user_branches -> 400 VALIDATION_FAILED on veterinarianId
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchActive, patientActive, vetWithoutAnyBranch, "09:00", "09:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("veterinarianId"));

        // Active user without VETERINARIO role -> 400 VALIDATION_FAILED on veterinarianId
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenantA, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchActive, patientActive, recepUser, "09:00", "09:30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("veterinarianId"));
    }

    @Test
    void detectsOverlapsAllowsAdjacentAndCancelledAndHandlesConcurrentRequestsSafely() throws Exception {
        TenantFixture tenant = createTenantFixture("create-overlap");
        UUID branchA = insertBranch(tenant.tenantId, "Sede Miraflores", "America/Lima", true);
        UUID branchB = insertBranch(tenant.tenantId, "Sede San Isidro", "America/Lima", true);
        UUID vetAna = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-ov@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vetAna, branchA);
        assignUserToBranch(tenant.tenantId, vetAna, branchB);

        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "74445555");
        UUID patientLuna = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");
        UUID patientMilo = insertPatient(tenant.tenantId, owner, "Milo", "GATO", "Siames");

        // 1. Create initial appointment 09:00 - 09:30
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchA, patientLuna, vetAna, "09:00", "09:30")))
                .andExpect(status().isCreated());

        // 2. Overlapping appointment 09:15 - 09:45 on another branch for the same veterinarian -> 409 Conflict
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchB, patientMilo, vetAna, "09:15", "09:45")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_TIME_CONFLICT"));

        // 3. Adjacent appointment 09:30 - 10:00 -> 201 Created
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchA, patientMilo, vetAna, "09:30", "10:00")))
                .andExpect(status().isCreated());

        // 4. Cancelled appointment at 10:00 - 10:30 (15:00Z - 15:30Z) does NOT block a new appointment
        insertAppointment(
                tenant.tenantId,
                branchA,
                patientLuna,
                vetAna,
                Instant.parse("2026-09-28T15:00:00Z"),
                Instant.parse("2026-09-28T15:30:00Z"),
                "CONSULTA_GENERAL",
                "CANCELADA",
                "Cita cancelada",
                null);

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branchA, patientMilo, vetAna, "10:00", "10:30")))
                .andExpect(status().isCreated());

        // 5. Real concurrent overlap test for 11:00 - 11:30
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task1 = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/appointments")
                                .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(appointmentPayload(branchA, patientLuna, vetAna, "11:00", "11:30")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };
            Callable<Integer> task2 = () -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/appointments")
                                .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(appointmentPayload(branchB, patientMilo, vetAna, "11:00", "11:30")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            };

            Future<Integer> future1 = pool.submit(task1);
            Future<Integer> future2 = pool.submit(task2);
            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            int status1 = future1.get(10, TimeUnit.SECONDS);
            int status2 = future2.get(10, TimeUnit.SECONDS);

            assertThat(List.of(status1, status2)).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rollsBackAppointmentAndSuppressesEventWhenAuditWriteFails() throws Exception {
        TenantFixture tenant = createTenantFixture("create-rollback");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-rb@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "75556666");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        reset(eventListener);
        doThrow(new IllegalStateException("Fallo simulado de auditoría"))
                .when(auditLogWriter)
                .append(any(AuditRecord.class));

        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branch, patient, vet, "12:00", "12:30")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE tenant_id = ?",
                Integer.class,
                tenant.tenantId);
        assertThat(count).isZero();
        verify(eventListener, never()).onAppointmentCreated(any(AppointmentCreated.class));
    }

    @Test
    void assignsAppointmentCreateOnlyToAdministradorAndRecepcionistaInV12() {
        TenantFixture tenant = createTenantFixture("rbac-v12");
        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenant.tenantId);

        List<String> rolesWithCreate = jdbc.queryForList("""
                SELECT r.name
                FROM roles r
                JOIN role_permissions rp
                  ON rp.tenant_id = r.tenant_id
                 AND rp.role_id = r.id
                JOIN permissions p
                  ON p.id = rp.permission_id
                WHERE r.tenant_id = ?
                  AND p.code = 'APPOINTMENT_CREATE'
                ORDER BY r.name ASC
                """, String.class, tenant.tenantId);

        assertThat(rolesWithCreate).containsExactly("ADMINISTRADOR", "RECEPCIONISTA");
    }

    @Test
    void transitionsValidStatusesIdempotentlyAndPersistsAppendOnlyHistoryAndSafeAudit() throws Exception {
        TenantFixture tenant = createTenantFixture("status-valid");
        UUID branch = insertBranch(tenant.tenantId, "Sede Miraflores", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-st@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "76001122");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Golden Retriever");

        // Appointment 1: PROGRAMADA -> CONFIRMADA -> CONFIRMADA (idempotent) -> EN_ESPERA -> EN_ESPERA (idempotent)
        UUID appt1 = insertAppointment(
                tenant.tenantId,
                branch,
                patient,
                vet,
                Instant.parse("2026-09-29T14:00:00Z"),
                Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL",
                "PROGRAMADA",
                "Control general",
                "Notas internas");

        String corrConfirm = "22222222-3333-4444-8555-666666666666";
        String confirmResp = mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt1)
                        .header("X-Correlation-ID", corrConfirm)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "CONFIRMADA" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appt1.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.patientId").value(patient.toString()))
                .andExpect(jsonPath("$.patientName").value("Luna"))
                .andExpect(jsonPath("$.branchId").value(branch.toString()))
                .andExpect(jsonPath("$.branchName").value("Sede Miraflores"))
                .andExpect(jsonPath("$.veterinarianId").value(vet.toString()))
                .andExpect(jsonPath("$.veterinarianFullName").value("Ana Salazar"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(confirmResp).doesNotContain("tenantId", "createdBy", "reason", "notes", "passwordHash");

        // Repeating CONFIRMADA is idempotent: 200 OK without new history or audit
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt1)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "CONFIRMADA" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"));

        Integer historyCountAfterIdempotentConfirm = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appt1);
        assertThat(historyCountAfterIdempotentConfirm).isEqualTo(1);

        Integer auditCountAfterIdempotentConfirm = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'",
                Integer.class,
                tenant.tenantId,
                appt1);
        assertThat(auditCountAfterIdempotentConfirm).isEqualTo(1);

        // Transition CONFIRMADA -> EN_ESPERA
        String corrWait = "33333333-4444-4555-8666-777777777777";
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt1)
                        .header("X-Correlation-ID", corrWait)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "EN_ESPERA" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ESPERA"));

        // Repeating EN_ESPERA is idempotent: 200 OK without new history or audit
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt1)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "EN_ESPERA" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ESPERA"));

        Integer historyCountAfterWait = jdbc.queryForObject(
                "SELECT COUNT(*) FROM appointment_status_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenant.tenantId,
                appt1);
        assertThat(historyCountAfterWait).isEqualTo(2);

        List<String> transitionsForAppt1 = jdbc.queryForList("""
                SELECT previous_status || '->' || new_status || ':' || changed_by::text || ':' || correlation_id::text
                FROM appointment_status_history
                WHERE tenant_id = ? AND appointment_id = ?
                ORDER BY changed_at ASC, id ASC
                """, String.class, tenant.tenantId, appt1);
        assertThat(transitionsForAppt1).containsExactly(
                "PROGRAMADA->CONFIRMADA:" + tenant.userId + ":" + corrConfirm,
                "CONFIRMADA->EN_ESPERA:" + tenant.userId + ":" + corrWait);

        // Appointment 2: Direct transition PROGRAMADA -> EN_ESPERA
        UUID appt2 = insertAppointment(
                tenant.tenantId,
                branch,
                patient,
                vet,
                Instant.parse("2026-09-29T15:00:00Z"),
                Instant.parse("2026-09-29T15:30:00Z"),
                "VACUNACION",
                "PROGRAMADA",
                "Vacuna anual",
                null);

        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "status": "EN_ESPERA" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ESPERA"));

        String appt2Transition = jdbc.queryForObject("""
                SELECT previous_status || '->' || new_status
                FROM appointment_status_history
                WHERE tenant_id = ? AND appointment_id = ?
                """, String.class, tenant.tenantId, appt2);
        assertThat(appt2Transition).isEqualTo("PROGRAMADA->EN_ESPERA");

        // Verify safe audit data structure
        String auditNewData = jdbc.queryForObject("""
                SELECT new_data::text
                FROM audit_entries
                WHERE tenant_id = ? AND entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'
                """, String.class, tenant.tenantId, appt2);
        assertThat(auditNewData)
                .contains("\"previousStatus\": \"PROGRAMADA\"")
                .contains("\"newStatus\": \"EN_ESPERA\"")
                .doesNotContain("reason", "notes", "passwordHash");
    }

    @Test
    void rejectsInvalidStatusTransitionsOutOfScopeTargetsAndInvalidBodies() throws Exception {
        TenantFixture tenant = createTenantFixture("status-invalid");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-inv@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "76002233");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        UUID apptProgramada = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);

        UUID apptEnEspera = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T15:00:00Z"), Instant.parse("2026-09-29T15:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", null, null);

        UUID apptCancelada = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T16:00:00Z"), Instant.parse("2026-09-29T16:30:00Z"),
                "CONSULTA_GENERAL", "CANCELADA", null, null);

        UUID apptFinalizada = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T17:00:00Z"), Instant.parse("2026-09-29T17:30:00Z"),
                "CONSULTA_GENERAL", "FINALIZADA", null, null);

        // 409 for EN_ESPERA -> CONFIRMADA
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptEnEspera)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));

        // 409 for out-of-scope or disallowed targets on PROGRAMADA
        for (String disallowedTarget : List.of("PROGRAMADA", "EN_ATENCION", "FINALIZADA", "CANCELADA", "NO_ASISTIO", "OTRO_ESTADO")) {
            mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptProgramada)
                            .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"status\": \"" + disallowedTarget + "\" }"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));
        }

        // 409 for CANCELADA -> CONFIRMADA and FINALIZADA -> EN_ESPERA
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptCancelada)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));

        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptFinalizada)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"EN_ESPERA\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));

        // 400 for blank status
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptProgramada)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"   \" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 400 when client attempts to inject tenantId, previousStatus, changedBy, or changedAt
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptProgramada)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CONFIRMADA",
                                  "tenantId": "%s",
                                  "previousStatus": "PROGRAMADA",
                                  "changedBy": "%s",
                                  "changedAt": "2026-09-29T14:00:00Z"
                                }
                                """.formatted(tenant.tenantId, tenant.userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void enforcesRbacAndTenantIsolationForStatusTransitionsAndV13SystemRoles() throws Exception {
        TenantFixture tenantA = createTenantFixture("status-tenant-a");
        TenantFixture tenantB = createTenantFixture("status-tenant-b");

        UUID branchA = insertBranch(tenantA.tenantId, "Sede A", "America/Lima", true);
        UUID vetA = insertUserWithRole(tenantA.tenantId, "Ana", "Salazar", "ana-ta@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenantA.tenantId, vetA, branchA);
        UUID ownerA = insertOwner(tenantA.tenantId, "Lucia", "Paredes", "DNI", "76003344");
        UUID patientA = insertPatient(tenantA.tenantId, ownerA, "Luna", "PERRO", "Mestizo");
        UUID apptA = insertAppointment(
                tenantA.tenantId, branchA, patientA, vetA,
                Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);

        // 401 without authentication
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 403 for user with APPOINTMENT_READ / APPOINTMENT_CREATE but without APPOINTMENT_STATUS_UPDATE
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptA)
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ", "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Uniform 404 for non-existent UUID and for foreign tenant's appointment (Tenant B accessing Tenant A's appt)
        UUID missingId = UUID.randomUUID();
        String missingResp = mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", missingId)
                        .with(authentication(auth(tenantB, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String foreignResp = mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptA)
                        .with(authentication(auth(tenantB, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreignResp.replaceAll("\"correlationId\":\"[^\"]+\"", ""))
                .isEqualTo(missingResp.replaceAll("\"correlationId\":\"[^\"]+\"", ""));

        // Verify V13 system roles assign APPOINTMENT_STATUS_UPDATE only to ADMINISTRADOR and RECEPCIONISTA
        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenantA.tenantId);
        List<String> rolesWithStatusUpdate = jdbc.queryForList("""
                SELECT r.name
                FROM roles r
                JOIN role_permissions rp
                  ON rp.tenant_id = r.tenant_id
                 AND rp.role_id = r.id
                JOIN permissions p
                  ON p.id = rp.permission_id
                WHERE r.tenant_id = ?
                  AND p.code = 'APPOINTMENT_STATUS_UPDATE'
                ORDER BY r.name ASC
                """, String.class, tenantA.tenantId);
        assertThat(rolesWithStatusUpdate).containsExactly("ADMINISTRADOR", "RECEPCIONISTA");
    }

    @Test
    void rollsBackStatusChangeWhenHistoryOrAuditFailsAndEnforcesAppendOnlyHistoryTrigger() throws Exception {
        TenantFixture tenant = createTenantFixture("status-rollback");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-srb@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "76004455");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");
        UUID appt = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);

        // 1. Fail when history insertion fails -> appointment stays PROGRAMADA and no audit is saved
        doThrow(new IllegalStateException("Fallo simulado en historial de estados"))
                .when(scheduleRepository)
                .insertStatusHistory(any(AppointmentStatusHistoryRecord.class));

        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt))
                .isEqualTo("PROGRAMADA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'", Integer.class, appt))
                .isZero();

        reset(scheduleRepository);

        // 2. Fail when audit write fails -> appointment stays PROGRAMADA and history is rolled back
        doThrow(new IllegalStateException("Fallo simulado en auditoría de estado"))
                .when(auditLogWriter)
                .append(any(AuditRecord.class));

        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt))
                .isEqualTo("PROGRAMADA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt))
                .isZero();

        reset(auditLogWriter);

        // 3. Succeeds and verifies append-only trigger on appointment_status_history
        mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", appt)
                        .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"CONFIRMADA\" }"))
                .andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointment_status_history SET new_status = 'EN_ESPERA' WHERE appointment_id = ?", appt))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM appointment_status_history WHERE appointment_id = ?", appt))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE appointment_status_history"))
                .hasMessageContaining("append-only");
    }

    @Test
    void handlesConcurrentStatusTransitionsConsistentlyWithoutLostUpdatesOrDuplicates() throws Exception {
        TenantFixture tenant = createTenantFixture("status-conc");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-cc@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "76005566");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        // Case 1: Two concurrent identical transitions PROGRAMADA -> CONFIRMADA
        UUID apptSame = insertAppointment(
                tenant.tenantId, branch, patient, vet,
                Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);

        CountDownLatch ready1 = new CountDownLatch(2);
        CountDownLatch start1 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> taskA = () -> {
                ready1.countDown();
                start1.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptSame)
                                .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"status\": \"CONFIRMADA\" }"))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> taskB = () -> {
                ready1.countDown();
                start1.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptSame)
                                .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"status\": \"CONFIRMADA\" }"))
                        .andReturn().getResponse().getStatus();
            };

            Future<Integer> fA = pool.submit(taskA);
            Future<Integer> fB = pool.submit(taskB);
            assertThat(ready1.await(5, TimeUnit.SECONDS)).isTrue();
            start1.countDown();

            assertThat(fA.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(fB.get(10, TimeUnit.SECONDS)).isEqualTo(200);

            assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, apptSame))
                    .isEqualTo("CONFIRMADA");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptSame))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'", Integer.class, apptSame))
                    .isEqualTo(1);

            // Case 2: Two concurrent transitions (one CONFIRMADA, one EN_ESPERA) on PROGRAMADA
            UUID apptChain = insertAppointment(
                    tenant.tenantId, branch, patient, vet,
                    Instant.parse("2026-09-29T15:00:00Z"), Instant.parse("2026-09-29T15:30:00Z"),
                    "CONSULTA_GENERAL", "PROGRAMADA", null, null);

            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch start2 = new CountDownLatch(1);
            Callable<Integer> taskConfirm = () -> {
                ready2.countDown();
                start2.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptChain)
                                .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"status\": \"CONFIRMADA\" }"))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> taskWait = () -> {
                ready2.countDown();
                start2.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/appointments/{appointmentId}/status", apptChain)
                                .with(authentication(auth(tenant, "APPOINTMENT_STATUS_UPDATE")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"status\": \"EN_ESPERA\" }"))
                        .andReturn().getResponse().getStatus();
            };

            Future<Integer> fConfirm = pool.submit(taskConfirm);
            Future<Integer> fWait = pool.submit(taskWait);
            assertThat(ready2.await(5, TimeUnit.SECONDS)).isTrue();
            start2.countDown();

            int statusConfirm = fConfirm.get(10, TimeUnit.SECONDS);
            int statusWait = fWait.get(10, TimeUnit.SECONDS);

            assertThat(statusWait).isEqualTo(200);
            assertThat(statusConfirm).isIn(200, 409);
            assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, apptChain))
                    .isEqualTo("EN_ESPERA");

            int historyRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?",
                    Integer.class,
                    apptChain);
            int auditRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_STATUS_CHANGED'",
                    Integer.class,
                    apptChain);
            assertThat(historyRows).isEqualTo(auditRows);
            assertThat(historyRows).isEqualTo(statusConfirm == 200 ? 2 : 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String appointmentPayload(UUID branchId, UUID patientId, UUID veterinarianId, String startTime, String endTime) {
        return """
                {
                  "branchId": "%s",
                  "patientId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-28T%s",
                  "endsAtLocal": "2026-09-28T%s",
                  "appointmentType": "CONSULTA_GENERAL"
                }
                """.formatted(branchId, patientId, veterinarianId, startTime, endTime);
    }

    private void assignUserToBranch(UUID tenantId, UUID userId, UUID branchId) {
        jdbc.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, false, now())
                ON CONFLICT (tenant_id, user_id, branch_id) DO NOTHING
                """, tenantId, userId, branchId);
    }

    private TenantFixture createTenantFixture(String prefix) {
        UUID tenantId = UUID.randomUUID();
        createdTenantIds.add(tenantId);
        UUID branchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, ?, ?, 'STARTER', 'ACTIVO', now())
                """, tenantId, "Clínica " + prefix, slug);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, 'Sede Base', 'America/Lima', true, now())
                """, branchId, tenantId);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at)
                VALUES (?, ?, ?, '$2a$10$abcdefghijklmnopqrstuv', 'Operador', 'Agenda', 'ACTIVO', now(), now())
                """, userId, tenantId, "op-" + slug + "@petcare.test");
        return new TenantFixture(tenantId, branchId, userId);
    }

    private UUID insertBranch(UUID tenantId, String name, String timeZone, boolean active) {
        UUID branchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, branchId, tenantId, name, timeZone, active);
        return branchId;
    }

    private UUID insertUserWithRole(
            UUID tenantId,
            String firstName,
            String lastName,
            String email,
            String roleName,
            String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at)
                VALUES (?, ?, ?, '$2a$10$abcdefghijklmnopqrstuv', ?, ?, ?, now(), now())
                """, userId, tenantId, email, firstName, lastName, status);

        UUID roleId = jdbc.query("""
                SELECT id FROM roles WHERE tenant_id = ? AND upper(name) = upper(?)
                """, rs -> rs.next() ? UUID.fromString(rs.getString("id")) : null, tenantId, roleName);
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

    private UUID insertPatient(UUID tenantId, UUID ownerId, String name, String species, String breed) {
        UUID patientId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients (id, tenant_id, owner_id, name, species, breed, sex, status)
                VALUES (?, ?, ?, ?, ?, ?, 'MACHO', 'ACTIVO')
                """, patientId, tenantId, ownerId, name, species, breed);
        return patientId;
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
        jdbc.update("""
                INSERT INTO appointments (
                    id, tenant_id, branch_id, patient_id, veterinarian_id,
                    starts_at, ends_at, appointment_type, status, reason, notes, created_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
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
}
