package com.petcare.enterprise.appointments.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AppointmentControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    @AfterEach
    void cleanUpTenants() {
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
