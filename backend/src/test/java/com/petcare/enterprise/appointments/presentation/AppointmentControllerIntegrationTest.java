package com.petcare.enterprise.appointments.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository;
import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository.AppointmentStatusHistoryRecord;
import com.petcare.enterprise.appointments.domain.AppointmentCancelled;
import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import com.petcare.enterprise.appointments.domain.AppointmentRescheduled;
import com.petcare.enterprise.appointments.infrastructure.events.AppointmentCancelledEventListener;
import com.petcare.enterprise.appointments.infrastructure.events.AppointmentCreatedEventListener;
import com.petcare.enterprise.appointments.infrastructure.events.AppointmentRescheduledEventListener;
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
    AppointmentRescheduledEventListener rescheduledEventListener;

    @MockitoSpyBean
    AppointmentCancelledEventListener cancelledEventListener;

    @MockitoSpyBean
    AuditLogWriter auditLogWriter;

    @MockitoSpyBean
    AppointmentScheduleRepository scheduleRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @AfterEach
    void cleanUpTenants() {
        reset(auditLogWriter, eventListener, rescheduledEventListener, cancelledEventListener, scheduleRepository);
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

    @Test
    void reschedulesProgramadaAndConfirmadaAppointmentsWithStatusReversionHistoryAuditAndAfterCommitEvent() throws Exception {
        TenantFixture tenant = createTenantFixture("resched-valid");
        UUID branchCentral = insertBranch(tenant.tenantId, "Sede Miraflores", "America/Lima", true);
        UUID branchSanBorja = insertBranch(tenant.tenantId, "Sede San Borja", "America/Lima", true);

        UUID vetAna = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-rs@petcare.test", "VETERINARIO", "ACTIVO");
        UUID vetCarlos = insertUserWithRole(tenant.tenantId, "Carlos", "Vega", "carlos-rs@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vetAna, branchCentral);
        assignUserToBranch(tenant.tenantId, vetCarlos, branchSanBorja);

        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "77001122");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Golden Retriever");

        // 1. Appointment in CONFIRMADA at 09:00-09:30 (14:00Z-14:30Z)
        UUID apptConfirmada = insertAppointment(
                tenant.tenantId,
                branchCentral,
                patient,
                vetAna,
                Instant.parse("2026-09-30T14:00:00Z"),
                Instant.parse("2026-09-30T14:30:00Z"),
                "VACUNACION",
                "CONFIRMADA",
                "Vacuna séxtuple",
                "Notas internas de vacunación");

        reset(rescheduledEventListener);
        String corrResched1 = "44444444-5555-4666-8777-888888888888";
        String reschedPayload1 = """
                {
                  "branchId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-30T11:00",
                  "endsAtLocal": "2026-09-30T11:45"
                }
                """.formatted(branchSanBorja, vetCarlos);

        String respJson = mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptConfirmada)
                        .header("X-Correlation-ID", corrResched1)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedPayload1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(apptConfirmada.toString()))
                .andExpect(jsonPath("$.status").value("PROGRAMADA"))
                .andExpect(jsonPath("$.branchId").value(branchSanBorja.toString()))
                .andExpect(jsonPath("$.branchName").value("Sede San Borja"))
                .andExpect(jsonPath("$.veterinarianId").value(vetCarlos.toString()))
                .andExpect(jsonPath("$.veterinarianFullName").value("Carlos Vega"))
                .andExpect(jsonPath("$.startsAt").value("2026-09-30T16:00:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2026-09-30T16:45:00Z"))
                .andExpect(jsonPath("$.appointmentType").value("VACUNACION"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(respJson).doesNotContain("tenantId", "createdBy", "reason", "notes", "cancellationReason");

        // Verify patient, type, reason and notes were preserved intact in DB
        var dbRow = jdbc.queryForMap("SELECT patient_id, appointment_type, reason, notes, status FROM appointments WHERE id = ?", apptConfirmada);
        assertThat(dbRow.get("patient_id")).isEqualTo(patient);
        assertThat(dbRow.get("appointment_type")).isEqualTo("VACUNACION");
        assertThat(dbRow.get("reason")).isEqualTo("Vacuna séxtuple");
        assertThat(dbRow.get("notes")).isEqualTo("Notas internas de vacunación");
        assertThat(dbRow.get("status")).isEqualTo("PROGRAMADA");

        // Verify append-only status history recorded CONFIRMADA -> PROGRAMADA
        List<String> historyTransitions = jdbc.queryForList("""
                SELECT previous_status || '->' || new_status || ':' || changed_by::text || ':' || correlation_id::text
                FROM appointment_status_history
                WHERE tenant_id = ? AND appointment_id = ?
                """, String.class, tenant.tenantId, apptConfirmada);
        assertThat(historyTransitions).containsExactly(
                "CONFIRMADA->PROGRAMADA:" + tenant.userId + ":" + corrResched1);

        // Verify audit entry APPOINTMENT_RESCHEDULED with previous and new schedule + status
        String auditOldData = jdbc.queryForObject(
                "SELECT old_data::text FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'",
                String.class, tenant.tenantId, apptConfirmada);
        String auditNewData = jdbc.queryForObject(
                "SELECT new_data::text FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'",
                String.class, tenant.tenantId, apptConfirmada);
        assertThat(auditOldData)
                .contains(branchCentral.toString(), vetAna.toString(), "2026-09-30T14:00:00Z", "CONFIRMADA")
                .doesNotContain("reason", "notes");
        assertThat(auditNewData)
                .contains(branchSanBorja.toString(), vetCarlos.toString(), "2026-09-30T16:00:00Z", "PROGRAMADA")
                .doesNotContain("reason", "notes");

        // Verify AFTER_COMMIT event AppointmentRescheduled fired once
        ArgumentCaptor<AppointmentRescheduled> evtCaptor = ArgumentCaptor.forClass(AppointmentRescheduled.class);
        verify(rescheduledEventListener, times(1)).onAppointmentRescheduled(evtCaptor.capture());
        AppointmentRescheduled evt = evtCaptor.getValue();
        assertThat(evt.tenantId()).isEqualTo(tenant.tenantId);
        assertThat(evt.appointmentId()).isEqualTo(apptConfirmada);
        assertThat(evt.previousBranchId()).isEqualTo(branchCentral);
        assertThat(evt.newBranchId()).isEqualTo(branchSanBorja);
        assertThat(evt.previousVeterinarianId()).isEqualTo(vetAna);
        assertThat(evt.newVeterinarianId()).isEqualTo(vetCarlos);
        assertThat(evt.previousStatus()).isEqualTo("CONFIRMADA");
        assertThat(evt.newStatus()).isEqualTo("PROGRAMADA");
        assertThat(evt.correlationId()).isEqualTo(UUID.fromString(corrResched1));

        // 2. Idempotency: submitting exact same branch, veterinarian and schedule produces 200 without new history, audit or event
        reset(rescheduledEventListener);
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptConfirmada)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reschedPayload1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROGRAMADA"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptConfirmada))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptConfirmada))
                .isEqualTo(1);
        verify(rescheduledEventListener, never()).onAppointmentRescheduled(any(AppointmentRescheduled.class));

        // 3. Rescheduling an already PROGRAMADA appointment to a partially overlapping slot with itself (11:15-12:00) succeeds without status history row
        String selfOverlapPayload = """
                {
                  "branchId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-30T11:15",
                  "endsAtLocal": "2026-09-30T12:00"
                }
                """.formatted(branchSanBorja, vetCarlos);

        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptConfirmada)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selfOverlapPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2026-09-30T16:15:00Z"))
                .andExpect(jsonPath("$.endsAt").value("2026-09-30T17:00:00Z"));

        // Status history remains 1 (since it was PROGRAMADA -> PROGRAMADA), while audit count becomes 2
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptConfirmada))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptConfirmada))
                .isEqualTo(2);
    }

    @Test
    void rescheduleValidatesOverlapsAdjacentSlotsAndRollsBackWhenAuditOrHistoryFails() throws Exception {
        TenantFixture tenant = createTenantFixture("resched-overlap");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID branchAlt = insertBranch(tenant.tenantId, "Sede Alterna", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-ro@petcare.test", "VETERINARIO", "ACTIVO");
        UUID vetAlt = insertUserWithRole(tenant.tenantId, "Carlos", "Vega", "carlos-ro@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        assignUserToBranch(tenant.tenantId, vetAlt, branchAlt);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "77002233");
        UUID patient1 = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");
        UUID patient2 = insertPatient(tenant.tenantId, owner, "Milo", "GATO", "Siames");

        // Appt 1 at 09:00-09:30 (14:00Z-14:30Z)
        UUID appt1 = insertAppointment(
                tenant.tenantId, branch, patient1, vet,
                Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);

        // Appt 2 at 10:00-10:30 (15:00Z-15:30Z)
        UUID appt2 = insertAppointment(
                tenant.tenantId, branch, patient2, vet,
                Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-09-30T15:30:00Z"),
                "CONSULTA_GENERAL", "CONFIRMADA", null, null);

        // 1. Reschedule appt2 to overlap appt1 (09:15-09:45) -> 409 APPOINTMENT_TIME_CONFLICT
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId": "%s",
                                  "veterinarianId": "%s",
                                  "startsAtLocal": "2026-09-30T09:15",
                                  "endsAtLocal": "2026-09-30T09:45"
                                }
                                """.formatted(branch, vet)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_TIME_CONFLICT"));

        // 2. Rollback when insertStatusHistory fails during reschedule of CONFIRMADA appointment:
        // preserves original schedule, branch, veterinarian and status, with 0 history, 0 audit and 0 events
        reset(rescheduledEventListener, scheduleRepository, auditLogWriter);
        doThrow(new IllegalStateException("Fallo simulado de historial en reprogramación"))
                .when(scheduleRepository)
                .insertStatusHistory(any(AppointmentStatusHistoryRecord.class));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId": "%s",
                                  "veterinarianId": "%s",
                                  "startsAtLocal": "2026-09-30T11:30",
                                  "endsAtLocal": "2026-09-30T12:00"
                                }
                                """.formatted(branchAlt, vetAlt)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        var appt2AfterHistoryFail = jdbc.queryForMap(
                "SELECT branch_id, veterinarian_id, starts_at, ends_at, status FROM appointments WHERE id = ?",
                appt2);
        assertThat(appt2AfterHistoryFail.get("branch_id")).isEqualTo(branch);
        assertThat(appt2AfterHistoryFail.get("veterinarian_id")).isEqualTo(vet);
        assertThat(((Timestamp) appt2AfterHistoryFail.get("starts_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(((Timestamp) appt2AfterHistoryFail.get("ends_at")).toInstant())
                .isEqualTo(Instant.parse("2026-09-30T15:30:00Z"));
        assertThat(appt2AfterHistoryFail.get("status")).isEqualTo("CONFIRMADA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt2))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, appt2))
                .isZero();
        verify(rescheduledEventListener, never()).onAppointmentRescheduled(any(AppointmentRescheduled.class));

        reset(scheduleRepository);

        // 3. Rollback when audit fails during reschedule of CONFIRMADA appointment -> stays CONFIRMADA at 10:00-10:30, no history or event
        reset(rescheduledEventListener);
        doThrow(new IllegalStateException("Fallo simulado de auditoría en reprogramación"))
                .when(auditLogWriter)
                .append(any(AuditRecord.class));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId": "%s",
                                  "veterinarianId": "%s",
                                  "startsAtLocal": "2026-09-30T09:30",
                                  "endsAtLocal": "2026-09-30T10:00"
                                }
                                """.formatted(branch, vet)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, appt2))
                .isEqualTo("CONFIRMADA");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt2))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, appt2))
                .isZero();
        verify(rescheduledEventListener, never()).onAppointmentRescheduled(any(AppointmentRescheduled.class));

        reset(auditLogWriter);

        // 4. Rollback when insertStatusHistory fails during cancellation -> preserves original status and null cancellation metadata, 0 history, 0 audit, 0 events
        reset(cancelledEventListener, scheduleRepository, auditLogWriter);
        doThrow(new IllegalStateException("Fallo simulado de historial en cancelación"))
                .when(scheduleRepository)
                .insertStatusHistory(any(AppointmentStatusHistoryRecord.class));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"Motivo que debe revertirse por fallo en historial\" }"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        var appt2AfterCancelHistoryFail = jdbc.queryForMap(
                "SELECT status, cancellation_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?",
                appt2);
        assertThat(appt2AfterCancelHistoryFail.get("status")).isEqualTo("CONFIRMADA");
        assertThat(appt2AfterCancelHistoryFail.get("cancellation_reason")).isNull();
        assertThat(appt2AfterCancelHistoryFail.get("cancelled_at")).isNull();
        assertThat(appt2AfterCancelHistoryFail.get("cancelled_by")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt2))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, appt2))
                .isZero();
        verify(cancelledEventListener, never()).onAppointmentCancelled(any(AppointmentCancelled.class));

        reset(scheduleRepository);

        // 5. Rollback when AuditLogWriter fails during cancellation -> reverts status, cancellation_reason, cancelled_at, cancelled_by and history; AppointmentCancelled not processed
        doThrow(new IllegalStateException("Fallo simulado de auditoría en cancelación"))
                .when(auditLogWriter)
                .append(any(AuditRecord.class));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"Motivo que debe revertirse por fallo en auditoría\" }"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        var appt2AfterCancelAuditFail = jdbc.queryForMap(
                "SELECT status, cancellation_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?",
                appt2);
        assertThat(appt2AfterCancelAuditFail.get("status")).isEqualTo("CONFIRMADA");
        assertThat(appt2AfterCancelAuditFail.get("cancellation_reason")).isNull();
        assertThat(appt2AfterCancelAuditFail.get("cancelled_at")).isNull();
        assertThat(appt2AfterCancelAuditFail.get("cancelled_by")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, appt2))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, appt2))
                .isZero();
        verify(cancelledEventListener, never()).onAppointmentCancelled(any(AppointmentCancelled.class));

        reset(auditLogWriter);

        // 6. Adjacent slot 09:30-10:00 succeeds once audit and history are restored
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", appt2)
                        .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branchId": "%s",
                                  "veterinarianId": "%s",
                                  "startsAtLocal": "2026-09-30T09:30",
                                  "endsAtLocal": "2026-09-30T10:00"
                                }
                                """.formatted(branch, vet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROGRAMADA"));
    }

    @Test
    void handlesConcurrentReschedulesAndCancellationsWithSingleCasIdempotencyAnd409ConcurrentModification() throws Exception {
        TenantFixture tenant = createTenantFixture("hu018-conc");
        UUID branchA = insertBranch(tenant.tenantId, "Sede Miraflores", "America/Lima", true);
        UUID branchB = insertBranch(tenant.tenantId, "Sede San Borja", "America/Lima", true);
        UUID vetAna = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-c18@petcare.test", "VETERINARIO", "ACTIVO");
        UUID vetCarlos = insertUserWithRole(tenant.tenantId, "Carlos", "Vega", "carlos-c18@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vetAna, branchA);
        assignUserToBranch(tenant.tenantId, vetCarlos, branchB);

        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "77009988");
        UUID patient = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // =========================================================================
            // 1. Dos reprogramaciones concurrentes DIFERENTES:
            //    - exactamente una modifica la cita (200);
            //    - la otra obtiene 409 APPOINTMENT_CONCURRENT_MODIFICATION con correlationId;
            //    - horario/sede/veterinario final coincide íntegramente con la solicitud ganadora;
            //    - una sola auditoría y un solo evento.
            // =========================================================================
            UUID apptDiff = insertAppointment(
                    tenant.tenantId, branchA, patient, vetAna,
                    Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                    "CONSULTA_GENERAL", "CONFIRMADA", null, null);

            reset(rescheduledEventListener, scheduleRepository);
            CountDownLatch diffReadLatch = new CountDownLatch(2);
            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                UUID idArg = invocation.getArgument(1);
                if (apptDiff.equals(idArg) && diffReadLatch.getCount() > 0) {
                    diffReadLatch.countDown();
                    diffReadLatch.await(5, TimeUnit.SECONDS);
                }
                return res;
            }).when(scheduleRepository).findById(any(UUID.class), any(UUID.class));

            String payloadDiff1 = """
                    {
                      "branchId": "%s",
                      "veterinarianId": "%s",
                      "startsAtLocal": "2026-09-30T11:00",
                      "endsAtLocal": "2026-09-30T11:30"
                    }
                    """.formatted(branchA, vetAna);
            String payloadDiff2 = """
                    {
                      "branchId": "%s",
                      "veterinarianId": "%s",
                      "startsAtLocal": "2026-09-30T12:00",
                      "endsAtLocal": "2026-09-30T12:45"
                    }
                    """.formatted(branchB, vetCarlos);

            Future<org.springframework.mock.web.MockHttpServletResponse> fDiff1 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptDiff)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloadDiff1))
                            .andReturn().getResponse());
            Future<org.springframework.mock.web.MockHttpServletResponse> fDiff2 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptDiff)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloadDiff2))
                            .andReturn().getResponse());

            var respDiff1 = fDiff1.get(10, TimeUnit.SECONDS);
            var respDiff2 = fDiff2.get(10, TimeUnit.SECONDS);
            reset(scheduleRepository);

            assertThat(List.of(respDiff1.getStatus(), respDiff2.getStatus()))
                    .containsExactlyInAnyOrder(200, 409);

            var conflictRespDiff = respDiff1.getStatus() == 409 ? respDiff1 : respDiff2;
            assertThat(conflictRespDiff.getContentAsString())
                    .contains("\"code\":\"APPOINTMENT_CONCURRENT_MODIFICATION\"")
                    .contains("\"correlationId\":");

            var diffRow = jdbc.queryForMap(
                    "SELECT branch_id, veterinarian_id, starts_at, ends_at, status FROM appointments WHERE id = ?",
                    apptDiff);
            assertThat(diffRow.get("status")).isEqualTo("PROGRAMADA");
            if (respDiff1.getStatus() == 200) {
                assertThat(diffRow.get("branch_id")).isEqualTo(branchA);
                assertThat(diffRow.get("veterinarian_id")).isEqualTo(vetAna);
                assertThat(((Timestamp) diffRow.get("starts_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
                assertThat(((Timestamp) diffRow.get("ends_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T16:30:00Z"));
            } else {
                assertThat(diffRow.get("branch_id")).isEqualTo(branchB);
                assertThat(diffRow.get("veterinarian_id")).isEqualTo(vetCarlos);
                assertThat(((Timestamp) diffRow.get("starts_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
                assertThat(((Timestamp) diffRow.get("ends_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T17:45:00Z"));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptDiff))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptDiff))
                    .isEqualTo(1);
            verify(rescheduledEventListener, times(1)).onAppointmentRescheduled(any(AppointmentRescheduled.class));

            // =========================================================================
            // 2. Dos reprogramaciones concurrentes IDÉNTICAS:
            //    - ambas responden 200;
            //    - una sola modificación, un historial, una auditoría y un evento.
            // =========================================================================
            UUID apptIdentical = insertAppointment(
                    tenant.tenantId, branchA, patient, vetAna,
                    Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                    "CONSULTA_GENERAL", "CONFIRMADA", null, null);

            reset(rescheduledEventListener, scheduleRepository);
            CountDownLatch sameReadLatch = new CountDownLatch(2);
            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                UUID idArg = invocation.getArgument(1);
                if (apptIdentical.equals(idArg) && sameReadLatch.getCount() > 0) {
                    sameReadLatch.countDown();
                    sameReadLatch.await(5, TimeUnit.SECONDS);
                }
                return res;
            }).when(scheduleRepository).findById(any(UUID.class), any(UUID.class));

            String payloadSame = """
                    {
                      "branchId": "%s",
                      "veterinarianId": "%s",
                      "startsAtLocal": "2026-09-30T13:00",
                      "endsAtLocal": "2026-09-30T13:30"
                    }
                    """.formatted(branchB, vetCarlos);

            Future<Integer> fSame1 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptIdentical)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloadSame))
                            .andReturn().getResponse().getStatus());
            Future<Integer> fSame2 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptIdentical)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payloadSame))
                            .andReturn().getResponse().getStatus());

            assertThat(fSame1.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(fSame2.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            reset(scheduleRepository);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptIdentical))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptIdentical))
                    .isEqualTo(1);
            verify(rescheduledEventListener, times(1)).onAppointmentRescheduled(any(AppointmentRescheduled.class));

            // =========================================================================
            // 3. Dos cancelaciones concurrentes:
            //    - ambas responden 200 idempotentemente;
            //    - solo una transición a CANCELADA;
            //    - un historial, una auditoría y un evento;
            //    - motivo original preservado.
            // =========================================================================
            UUID apptDoubleCancel = insertAppointment(
                    tenant.tenantId, branchA, patient, vetAna,
                    Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-09-30T15:30:00Z"),
                    "CONSULTA_GENERAL", "CONFIRMADA", null, null);

            reset(cancelledEventListener, scheduleRepository);
            CountDownLatch cancelReadLatch = new CountDownLatch(2);
            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                UUID idArg = invocation.getArgument(1);
                if (apptDoubleCancel.equals(idArg) && cancelReadLatch.getCount() > 0) {
                    cancelReadLatch.countDown();
                    cancelReadLatch.await(5, TimeUnit.SECONDS);
                }
                return res;
            }).when(scheduleRepository).findById(any(UUID.class), any(UUID.class));

            String reasonA = "Motivo de cancelación concurrente A";
            String reasonB = "Motivo de cancelación concurrente B";

            Future<Integer> fCancel1 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptDoubleCancel)
                                    .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"reason\": \"" + reasonA + "\" }"))
                            .andReturn().getResponse().getStatus());
            Future<Integer> fCancel2 = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptDoubleCancel)
                                    .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"reason\": \"" + reasonB + "\" }"))
                            .andReturn().getResponse().getStatus());

            assertThat(fCancel1.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(fCancel2.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            reset(scheduleRepository);

            String savedReason = jdbc.queryForObject(
                    "SELECT cancellation_reason FROM appointments WHERE id = ?",
                    String.class,
                    apptDoubleCancel);
            assertThat(savedReason).isIn(reasonA, reasonB);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptDoubleCancel))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, apptDoubleCancel))
                    .isEqualTo(1);
            String auditedCancelJson = jdbc.queryForObject(
                    "SELECT new_data::text FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'",
                    String.class,
                    apptDoubleCancel);
            assertThat(auditedCancelJson).contains(savedReason);
            verify(cancelledEventListener, times(1)).onAppointmentCancelled(any(AppointmentCancelled.class));

            // =========================================================================
            // 4. Reprogramación y cancelación concurrentes:
            //    - no dejan combinación parcial de horario, estado o metadatos;
            //    - una operación gana (200) y la incompatible devuelve 409 APPOINTMENT_CONCURRENT_MODIFICATION;
            //    - historial y auditoría coinciden con el resultado final.
            // =========================================================================
            UUID apptRace = insertAppointment(
                    tenant.tenantId, branchA, patient, vetAna,
                    Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                    "CONSULTA_GENERAL", "CONFIRMADA", null, null);

            reset(rescheduledEventListener, cancelledEventListener, scheduleRepository);
            CountDownLatch raceReadLatch = new CountDownLatch(2);
            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                UUID idArg = invocation.getArgument(1);
                if (apptRace.equals(idArg) && raceReadLatch.getCount() > 0) {
                    raceReadLatch.countDown();
                    raceReadLatch.await(5, TimeUnit.SECONDS);
                }
                return res;
            }).when(scheduleRepository).findById(any(UUID.class), any(UUID.class));

            String raceReschedPayload = """
                    {
                      "branchId": "%s",
                      "veterinarianId": "%s",
                      "startsAtLocal": "2026-09-30T15:00",
                      "endsAtLocal": "2026-09-30T15:30"
                    }
                    """.formatted(branchB, vetCarlos);

            Future<org.springframework.mock.web.MockHttpServletResponse> fRaceResched = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptRace)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(raceReschedPayload))
                            .andReturn().getResponse());
            Future<org.springframework.mock.web.MockHttpServletResponse> fRaceCancel = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptRace)
                                    .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"reason\": \"Cancelación en competencia con reprogramación\" }"))
                            .andReturn().getResponse());

            var respRaceResched = fRaceResched.get(10, TimeUnit.SECONDS);
            var respRaceCancel = fRaceCancel.get(10, TimeUnit.SECONDS);
            reset(scheduleRepository);

            assertThat(List.of(respRaceResched.getStatus(), respRaceCancel.getStatus()))
                    .containsExactlyInAnyOrder(200, 409);

            var losingRaceResp = respRaceResched.getStatus() == 409 ? respRaceResched : respRaceCancel;
            assertThat(losingRaceResp.getContentAsString())
                    .contains("\"code\":\"APPOINTMENT_CONCURRENT_MODIFICATION\"")
                    .contains("\"correlationId\":");

            var raceRow = jdbc.queryForMap(
                    "SELECT branch_id, veterinarian_id, starts_at, ends_at, status, cancellation_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?",
                    apptRace);

            if (respRaceResched.getStatus() == 200) {
                // Reschedule won: schedule updated to 15:00-15:30 on branchB/vetCarlos, status PROGRAMADA, cancellation metadata NULL
                assertThat(raceRow.get("status")).isEqualTo("PROGRAMADA");
                assertThat(raceRow.get("branch_id")).isEqualTo(branchB);
                assertThat(raceRow.get("veterinarian_id")).isEqualTo(vetCarlos);
                assertThat(((Timestamp) raceRow.get("starts_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T20:00:00Z"));
                assertThat(raceRow.get("cancellation_reason")).isNull();
                assertThat(raceRow.get("cancelled_at")).isNull();
                assertThat(raceRow.get("cancelled_by")).isNull();
                assertThat(jdbc.queryForObject("SELECT new_status FROM appointment_status_history WHERE appointment_id = ?", String.class, apptRace))
                        .isEqualTo("PROGRAMADA");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptRace))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, apptRace))
                        .isZero();
                verify(rescheduledEventListener, times(1)).onAppointmentRescheduled(any(AppointmentRescheduled.class));
                verify(cancelledEventListener, never()).onAppointmentCancelled(any(AppointmentCancelled.class));
            } else {
                // Cancel won: original schedule 09:00-09:30 on branchA/vetAna preserved, status CANCELADA, cancellation metadata populated
                assertThat(raceRow.get("status")).isEqualTo("CANCELADA");
                assertThat(raceRow.get("branch_id")).isEqualTo(branchA);
                assertThat(raceRow.get("veterinarian_id")).isEqualTo(vetAna);
                assertThat(((Timestamp) raceRow.get("starts_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T14:00:00Z"));
                assertThat(raceRow.get("cancellation_reason")).isEqualTo("Cancelación en competencia con reprogramación");
                assertThat(raceRow.get("cancelled_at")).isNotNull();
                assertThat(raceRow.get("cancelled_by")).isEqualTo(tenant.userId);
                assertThat(jdbc.queryForObject("SELECT new_status FROM appointment_status_history WHERE appointment_id = ?", String.class, apptRace))
                        .isEqualTo("CANCELADA");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, apptRace))
                        .isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptRace))
                        .isZero();
                verify(cancelledEventListener, times(1)).onAppointmentCancelled(any(AppointmentCancelled.class));
                verify(rescheduledEventListener, never()).onAppointmentRescheduled(any(AppointmentRescheduled.class));
            }

            // 4b. Reprogramación y cancelación concurrentes sobre cita PROGRAMADA (donde reprogramar mantiene PROGRAMADA):
            //     cuando reprogramar ejecuta CAS primero, cancelar detecta el cambio de horario y devuelve 409 APPOINTMENT_CONCURRENT_MODIFICATION
            UUID apptRaceProgramada = insertAppointment(
                    tenant.tenantId, branchA, patient, vetAna,
                    Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                    "CONSULTA_GENERAL", "PROGRAMADA", null, null);

            reset(rescheduledEventListener, cancelledEventListener, scheduleRepository);
            CountDownLatch progReadLatch = new CountDownLatch(2);
            CountDownLatch reschedCasDoneLatch = new CountDownLatch(1);
            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                UUID idArg = invocation.getArgument(1);
                if (apptRaceProgramada.equals(idArg) && progReadLatch.getCount() > 0) {
                    progReadLatch.countDown();
                    progReadLatch.await(5, TimeUnit.SECONDS);
                }
                return res;
            }).when(scheduleRepository).findById(any(UUID.class), any(UUID.class));

            doAnswer(invocation -> {
                Object res = invocation.callRealMethod();
                reschedCasDoneLatch.countDown();
                return res;
            }).when(scheduleRepository).rescheduleConditionally(any());

            doAnswer(invocation -> {
                reschedCasDoneLatch.await(5, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(scheduleRepository).cancelConditionally(any());

            String progReschedPayload = """
                    {
                      "branchId": "%s",
                      "veterinarianId": "%s",
                      "startsAtLocal": "2026-09-30T16:00",
                      "endsAtLocal": "2026-09-30T16:30"
                    }
                    """.formatted(branchB, vetCarlos);

            Future<org.springframework.mock.web.MockHttpServletResponse> fProgResched = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptRaceProgramada)
                                    .with(authentication(auth(tenant, "APPOINTMENT_RESCHEDULE")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(progReschedPayload))
                            .andReturn().getResponse());
            Future<org.springframework.mock.web.MockHttpServletResponse> fProgCancel = pool.submit(() ->
                    mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptRaceProgramada)
                                    .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"reason\": \"Cancelación tras reprogramación concurrente\" }"))
                            .andReturn().getResponse());

            var respProgResched = fProgResched.get(10, TimeUnit.SECONDS);
            var respProgCancel = fProgCancel.get(10, TimeUnit.SECONDS);
            reset(scheduleRepository);

            assertThat(respProgResched.getStatus()).isEqualTo(200);
            assertThat(respProgCancel.getStatus()).isEqualTo(409);
            assertThat(respProgCancel.getContentAsString())
                    .contains("\"code\":\"APPOINTMENT_CONCURRENT_MODIFICATION\"")
                    .contains("\"correlationId\":");

            var progRow = jdbc.queryForMap(
                    "SELECT branch_id, veterinarian_id, starts_at, ends_at, status, cancellation_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?",
                    apptRaceProgramada);
            assertThat(progRow.get("status")).isEqualTo("PROGRAMADA");
            assertThat(progRow.get("branch_id")).isEqualTo(branchB);
            assertThat(progRow.get("veterinarian_id")).isEqualTo(vetCarlos);
            assertThat(((Timestamp) progRow.get("starts_at")).toInstant()).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
            assertThat(progRow.get("cancellation_reason")).isNull();
            assertThat(progRow.get("cancelled_at")).isNull();
            assertThat(progRow.get("cancelled_by")).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptRaceProgramada))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_RESCHEDULED'", Integer.class, apptRaceProgramada))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, apptRaceProgramada))
                    .isZero();
            verify(rescheduledEventListener, times(1)).onAppointmentRescheduled(any(AppointmentRescheduled.class));
            verify(cancelledEventListener, never()).onAppointmentCancelled(any(AppointmentCancelled.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelsAppointmentsFromAllowedStatusesFreesSlotPreservesOriginalReasonIdempotentlyAndAuditsAtomically() throws Exception {
        TenantFixture tenant = createTenantFixture("cancel-valid");
        UUID branch = insertBranch(tenant.tenantId, "Sede Central", "America/Lima", true);
        UUID vet = insertUserWithRole(tenant.tenantId, "Ana", "Salazar", "ana-cn@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenant.tenantId, vet, branch);
        UUID owner = insertOwner(tenant.tenantId, "Lucia", "Paredes", "DNI", "77003344");
        UUID patient1 = insertPatient(tenant.tenantId, owner, "Luna", "PERRO", "Mestizo");
        UUID patient2 = insertPatient(tenant.tenantId, owner, "Milo", "GATO", "Siames");

        // Appointment in EN_ESPERA at 09:00-09:30 on 2026-09-28 (14:00Z-14:30Z)
        UUID apptEnEspera = insertAppointment(
                tenant.tenantId, branch, patient1, vet,
                Instant.parse("2026-09-28T14:00:00Z"), Instant.parse("2026-09-28T14:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", null, null);

        reset(cancelledEventListener);
        String corrCancel = "55555555-6666-4777-8888-999999999999";
        String originalReason = "Propietario solicitó cancelar por viaje imprevisto";

        String cancelResp = mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptEnEspera)
                        .header("X-Correlation-ID", corrCancel)
                        .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "  %s  " }
                                """.formatted(originalReason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(apptEnEspera.toString()))
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(cancelResp).doesNotContain("tenantId", "cancellationReason", "cancelledBy");

        // Verify metadata persisted in appointments table
        var dbRow = jdbc.queryForMap(
                "SELECT status, cancellation_reason, cancelled_at, cancelled_by FROM appointments WHERE id = ?",
                apptEnEspera);
        assertThat(dbRow.get("status")).isEqualTo("CANCELADA");
        assertThat(dbRow.get("cancellation_reason")).isEqualTo(originalReason);
        assertThat(dbRow.get("cancelled_at")).isNotNull();
        assertThat(dbRow.get("cancelled_by")).isEqualTo(tenant.userId);

        // Verify status history recorded EN_ESPERA -> CANCELADA
        String historyTransition = jdbc.queryForObject(
                "SELECT previous_status || '->' || new_status FROM appointment_status_history WHERE appointment_id = ?",
                String.class, apptEnEspera);
        assertThat(historyTransition).isEqualTo("EN_ESPERA->CANCELADA");

        // Verify APPOINTMENT_CANCELLED audit entry contains previousStatus, newStatus, cancelledBy and cancellationReason
        String auditNewData = jdbc.queryForObject(
                "SELECT new_data::text FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'",
                String.class, apptEnEspera);
        assertThat(auditNewData)
                .contains("\"previousStatus\": \"EN_ESPERA\"")
                .contains("\"newStatus\": \"CANCELADA\"")
                .contains("\"cancellationReason\": \"" + originalReason + "\"")
                .contains("\"cancelledBy\": \"" + tenant.userId + "\"")
                .doesNotContain("notes", "passwordHash");

        // Verify AFTER_COMMIT AppointmentCancelled event fired once
        ArgumentCaptor<AppointmentCancelled> evtCaptor = ArgumentCaptor.forClass(AppointmentCancelled.class);
        verify(cancelledEventListener, times(1)).onAppointmentCancelled(evtCaptor.capture());
        AppointmentCancelled evt = evtCaptor.getValue();
        assertThat(evt.appointmentId()).isEqualTo(apptEnEspera);
        assertThat(evt.previousStatus()).isEqualTo("EN_ESPERA");
        assertThat(evt.cancelledBy()).isEqualTo(tenant.userId);
        assertThat(evt.correlationId()).isEqualTo(UUID.fromString(corrCancel));

        // Verify idempotency: cancelling an already CANCELADA appointment with a different reason returns 200,
        // preserves original reason/metadata, and does NOT duplicate history, audit or event
        reset(cancelledEventListener);
        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptEnEspera)
                        .with(authentication(auth(tenant, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "Nuevo motivo enviado en reintento que no debe sobrescribir" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"));

        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM appointments WHERE id = ?", String.class, apptEnEspera))
                .isEqualTo(originalReason);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment_status_history WHERE appointment_id = ?", Integer.class, apptEnEspera))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_entries WHERE entity_id = ? AND action = 'APPOINTMENT_CANCELLED'", Integer.class, apptEnEspera))
                .isEqualTo(1);
        verify(cancelledEventListener, never()).onAppointmentCancelled(any(AppointmentCancelled.class));

        // Verify the freed 09:00-09:30 slot can immediately be booked for another appointment
        mvc.perform(post("/api/v1/appointments")
                        .with(authentication(auth(tenant, "APPOINTMENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(appointmentPayload(branch, patient2, vet, "09:00", "09:30")))
                .andExpect(status().isCreated());
    }

    @Test
    void enforcesRbacTenantIsolationInvalidTransitionsValidationAndV14Constraints() throws Exception {
        TenantFixture tenantA = createTenantFixture("hu018-sec-a");
        TenantFixture tenantB = createTenantFixture("hu018-sec-b");

        UUID branchA = insertBranch(tenantA.tenantId, "Sede A", "America/Lima", true);
        UUID vetA = insertUserWithRole(tenantA.tenantId, "Ana", "Salazar", "ana-s18@petcare.test", "VETERINARIO", "ACTIVO");
        assignUserToBranch(tenantA.tenantId, vetA, branchA);
        UUID ownerA = insertOwner(tenantA.tenantId, "Lucia", "Paredes", "DNI", "77004455");
        UUID patientA = insertPatient(tenantA.tenantId, ownerA, "Luna", "PERRO", "Mestizo");

        UUID apptProgramada = insertAppointment(
                tenantA.tenantId, branchA, patientA, vetA,
                Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"),
                "CONSULTA_GENERAL", "PROGRAMADA", null, null);
        UUID apptEnEspera = insertAppointment(
                tenantA.tenantId, branchA, patientA, vetA,
                Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-09-30T15:30:00Z"),
                "CONSULTA_GENERAL", "EN_ESPERA", null, null);
        UUID apptFinalizada = insertAppointment(
                tenantA.tenantId, branchA, patientA, vetA,
                Instant.parse("2026-09-30T16:00:00Z"), Instant.parse("2026-09-30T16:30:00Z"),
                "CONSULTA_GENERAL", "FINALIZADA", null, null);

        String validResched = """
                {
                  "branchId": "%s",
                  "veterinarianId": "%s",
                  "startsAtLocal": "2026-09-30T17:00",
                  "endsAtLocal": "2026-09-30T17:30"
                }
                """.formatted(branchA, vetA);

        // 1. 401 and 403 on reschedule and cancellation
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptProgramada)
                        .contentType(MediaType.APPLICATION_JSON).content(validResched))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptProgramada)
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"Motivo válido\" }"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptProgramada)
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ", "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON).content(validResched))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptProgramada)
                        .with(authentication(auth(tenantA, "APPOINTMENT_READ", "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"Motivo válido\" }"))
                .andExpect(status().isForbidden());

        // 2. Uniform 404 for foreign tenant appointment
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptProgramada)
                        .with(authentication(auth(tenantB, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON).content(validResched))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptProgramada)
                        .with(authentication(auth(tenantB, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"Motivo válido\" }"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 3. Disallowed source states -> 409 INVALID_APPOINTMENT_STATUS_TRANSITION
        mvc.perform(put("/api/v1/appointments/{appointmentId}/schedule", apptEnEspera)
                        .with(authentication(auth(tenantA, "APPOINTMENT_RESCHEDULE")))
                        .contentType(MediaType.APPLICATION_JSON).content(validResched))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptFinalizada)
                        .with(authentication(auth(tenantA, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"Motivo válido\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_APPOINTMENT_STATUS_TRANSITION"));

        // 4. Validation: blank/short cancellation reason or injected internal fields -> 400
        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptProgramada)
                        .with(authentication(auth(tenantA, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"reason\": \"ab\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/v1/appointments/{appointmentId}/cancellation", apptProgramada)
                        .with(authentication(auth(tenantA, "APPOINTMENT_CANCEL")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"Motivo válido\", \"cancelledBy\": \"" + tenantA.userId + "\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        // 5. Verify V14 system role assignments for APPOINTMENT_RESCHEDULE and APPOINTMENT_CANCEL
        jdbc.query("SELECT ensure_tenant_system_roles(?)", rs -> null, tenantA.tenantId);
        for (String permCode : List.of("APPOINTMENT_RESCHEDULE", "APPOINTMENT_CANCEL")) {
            List<String> assignedRoles = jdbc.queryForList("""
                    SELECT r.name
                    FROM roles r
                    JOIN role_permissions rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.tenant_id = ? AND p.code = ?
                    ORDER BY r.name ASC
                    """, String.class, tenantA.tenantId, permCode);
            assertThat(assignedRoles).containsExactly("ADMINISTRADOR", "RECEPCIONISTA");
        }

        // 6. Verify PostgreSQL ck_appointments_cancellation_metadata rejects CANCELADA without reason and non-CANCELADA with reason
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointments SET status = 'CANCELADA', cancellation_reason = NULL WHERE id = ?",
                apptProgramada))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointments SET cancellation_reason = 'Motivo inválido en programada' WHERE id = ?",
                apptProgramada))
                .isInstanceOf(DataIntegrityViolationException.class);
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
}
