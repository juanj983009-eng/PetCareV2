package com.petcare.enterprise.clinical.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PatientControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsPatientMedicalRecordAndAuditWithinTenant() throws Exception {
        Fixture f = fixture("patient-a");
        UUID owner = owner(f, true);
        mvc.perform(post("/api/v1/patients")
                        .with(authentication(auth(f, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(owner)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.containsString("/api/v1/patients/")))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.name").value("Luna"));
        assertCount("patients", f.tenant, 1);
        assertCount("medical_records", f.tenant, 1);
        assertCount("audit_entries", f.tenant, 1);
    }

    @Test
    void rejectsUnauthenticatedUnauthorizedAndForeignOrInactiveOwners() throws Exception {
        Fixture a = fixture("patient-owner-a"), b = fixture("patient-owner-b");
        UUID inactive = owner(a, false), foreign = owner(b, true);
        mvc.perform(post("/api/v1/patients").contentType(MediaType.APPLICATION_JSON).content(body(inactive)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "OWNER_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(inactive)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(inactive)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(foreign)))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesRequiredAndFutureBirthDate() throws Exception {
        Fixture f = fixture("patient-validation");
        UUID owner = owner(f, true);
        mvc.perform(post("/api/v1/patients").with(authentication(auth(f, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + owner + "\",\"name\":\"\",\"species\":\"PERRO\",\"sex\":\"HEMBRA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(f, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + owner + "\",\"name\":\"Luna\",\"species\":\"PERRO\",\"sex\":\"HEMBRA\",\"birthDate\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rollsBackPatientWhenMedicalRecordOrAuditFails() throws Exception {
        Fixture f = fixture("patient-atomic");
        UUID owner = owner(f, true);
        jdbc.execute("CREATE OR REPLACE FUNCTION reject_patient_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'history failure'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_patient_history_trigger BEFORE INSERT ON medical_records FOR EACH ROW EXECUTE FUNCTION reject_patient_history()");
        mvc.perform(post("/api/v1/patients").with(authentication(auth(f, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(owner)))
                .andExpect(status().isInternalServerError());
        assertCount("patients", f.tenant, 0);
        jdbc.execute("DROP TRIGGER reject_patient_history_trigger ON medical_records");
        jdbc.execute("DROP FUNCTION reject_patient_history()");
        mvc.perform(post("/api/v1/patients").with(authentication(auth(new Fixture(f.tenant, UUID.randomUUID(), f.user), "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(owner)))
                .andExpect(status().isConflict());
        assertCount("patients", f.tenant, 0);
    }

    @Test
    void scopesMicrochipUniquenessToTenantAndAllowsNulls() throws Exception {
        Fixture a = fixture("patient-chip-a"), b = fixture("patient-chip-b");
        UUID ownerA = owner(a, true), ownerB = owner(b, true);
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(ownerA)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(ownerA)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(b, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(ownerB)))
                .andExpect(status().isCreated());
        String withoutChip = "{\"ownerId\":\"" + ownerA + "\",\"name\":\"Sin chip\",\"species\":\"GATO\",\"sex\":\"MACHO\"}";
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(withoutChip))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/patients").with(authentication(auth(a, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(withoutChip.replace("Sin chip", "Sin chip dos")))
                .andExpect(status().isCreated());
    }

    @Test
    void persistsOnlySafeAuditMetadata() throws Exception {
        Fixture f = fixture("patient-audit");
        UUID owner = owner(f, true);
        mvc.perform(post("/api/v1/patients").with(authentication(auth(f, "PATIENT_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(owner)))
                .andExpect(status().isCreated());
        var audit = jdbc.queryForMap(
                "SELECT tenant_id,actor_user_id,correlation_id,new_data::text FROM audit_entries WHERE tenant_id=? AND action='PATIENT_CREATED'",
                f.tenant);
        assertThat(audit.get("tenant_id")).isEqualTo(f.tenant);
        assertThat(audit.get("actor_user_id")).isEqualTo(f.user);
        assertThat(audit.get("correlation_id")).isNotNull();
        assertThat(String.valueOf(audit.get("new_data")))
                .contains("name", "species", "sex")
                .doesNotContain("microchip", "birthDate", "sterilized");
    }

    @Test
    void searchesByPatientNameOwnerNameDocumentAndMicrochipWithFiltersPaginationSortingAndTenantIsolation() throws Exception {
        Fixture tenantA = fixture("search-patients-a");
        Fixture tenantB = fixture("search-patients-b");

        UUID ownerA1 = insertOwner(tenantA.tenant, "Lucía", "Paredes", "DNI", "70112233", "900111222");
        UUID ownerA2 = insertOwner(tenantA.tenant, "Carlos", "Mendoza", "CE", "00998877", "900333444");
        UUID ownerB1 = insertOwner(tenantB.tenant, "Lucía", "Ajena", "DNI", "70112233", "900999888");

        UUID miloId = insertPatientWithSensitiveRecord(tenantA.tenant, ownerA1, "Milo", "Canino", "Beagle", "MACHO", "CHIP-PE-1001", "ACTIVO");
        UUID kiraId = insertPatientWithSensitiveRecord(tenantA.tenant, ownerA1, "Kira", "Felino", "Siamés", "HEMBRA", "CHIP-PE-1002", "INACTIVO");
        UUID thorId = insertPatientWithSensitiveRecord(tenantA.tenant, ownerA2, "Thor", "Canino", "Labrador", "MACHO", "CHIP-PE-2001", "ACTIVO");
        insertPatientWithSensitiveRecord(tenantB.tenant, ownerB1, "Milo Ajeno", "Canino", "Pug", "MACHO", "CHIP-PE-1001", "ACTIVO");

        // 1. Partial patient name search (case-insensitive & trimmed) + verify DTO fields and non-exposure of clinical data
        String responseJson = mvc.perform(get("/api/v1/patients")
                        .param("query", "  mIl  ")
                        .param("tenantId", tenantB.tenant.toString())
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(miloId.toString()))
                .andExpect(jsonPath("$.content[0].patientId").doesNotExist())
                .andExpect(jsonPath("$.content[0].name").value("Milo"))
                .andExpect(jsonPath("$.content[0].species").value("Canino"))
                .andExpect(jsonPath("$.content[0].breed").value("Beagle"))
                .andExpect(jsonPath("$.content[0].sex").value("MACHO"))
                .andExpect(jsonPath("$.content[0].ownerId").value(ownerA1.toString()))
                .andExpect(jsonPath("$.content[0].ownerFullName").value("Lucía Paredes"))
                .andExpect(jsonPath("$.content[0].ownerDocumentType").value("DNI"))
                .andExpect(jsonPath("$.content[0].ownerDocumentNumber").value("70112233"))
                .andExpect(jsonPath("$.content[0].ownerFirstName").doesNotExist())
                .andExpect(jsonPath("$.content[0].ownerLastName").doesNotExist())
                .andExpect(jsonPath("$.content[0].ownerPhone").doesNotExist())
                .andExpect(jsonPath("$.content[0].microchip").value("CHIP-PE-1001"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVO"))
                .andReturn().getResponse().getContentAsString();
        assertThat(responseJson).doesNotContain(
                "patientId",
                "ownerFirstName",
                "ownerLastName",
                "ownerPhone",
                "allergies",
                "medicalAlerts",
                "history",
                "ALERGIAS_SECRETAS",
                "ALERTA_CRITICA_SECRETA");

        // 2. Search by owner full name
        mvc.perform(get("/api/v1/patients").param("query", "paredes")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // 3. Search by owner document number
        mvc.perform(get("/api/v1/patients").param("query", "998877")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(thorId.toString()));

        // 4. Search by microchip
        mvc.perform(get("/api/v1/patients").param("query", "pe-1002")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(kiraId.toString()));

        // 5. Filter by species, status, and combination of query + filters
        mvc.perform(get("/api/v1/patients")
                        .param("query", "paredes")
                        .param("species", "felino")
                        .param("status", "INACTIVO")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Kira"));

        // 6. Pagination & allowed sorting (sortBy=name, direction=desc)
        mvc.perform(get("/api/v1/patients")
                        .param("species", "Canino")
                        .param("status", "ACTIVO")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sortBy", "name")
                        .param("direction", "desc")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Thor"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // 7. Tenant B data is never discoverable by Tenant A
        mvc.perform(get("/api/v1/patients").param("query", "Ajeno")
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void enforcesAuthenticationAuthorizationValidationAndProvisioningForPatientRead() throws Exception {
        Fixture f = fixture("patient-read-security");
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(?)", Object.class, f.tenant);

        // Verify role provisioning in V9: ADMINISTRADOR, VETERINARIO, RECEPCIONISTA have PATIENT_READ; ALMACEN does not
        for (String roleWithPermission : List.of("ADMINISTRADOR", "VETERINARIO", "RECEPCIONISTA")) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM role_permissions rp
                    JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND r.name = ? AND p.code = 'PATIENT_READ'
                    """, Integer.class, f.tenant, roleWithPermission);
            assertThat(count).as("Role %s should have PATIENT_READ", roleWithPermission).isOne();
        }
        Integer almacenCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM role_permissions rp
                JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.tenant_id = ? AND r.name = 'ALMACEN' AND p.code = 'PATIENT_READ'
                """, Integer.class, f.tenant);
        assertThat(almacenCount).isZero();

        // 401 without JWT
        mvc.perform(get("/api/v1/patients")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 403 without PATIENT_READ
        mvc.perform(get("/api/v1/patients").with(authentication(auth(f, "DASHBOARD_VIEW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 400 when query has < 2 characters or whitespace only
        mvc.perform(get("/api/v1/patients").param("query", "a")
                        .with(authentication(auth(f, "PATIENT_READ"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/patients").param("query", "   ")
                        .with(authentication(auth(f, "PATIENT_READ"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 400 when sortBy or direction or status is not in allowlist
        mvc.perform(get("/api/v1/patients").param("sortBy", "password")
                        .with(authentication(auth(f, "PATIENT_READ"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/patients").param("direction", "random")
                        .with(authentication(auth(f, "PATIENT_READ"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/patients").param("status", "ELIMINADO")
                        .with(authentication(auth(f, "PATIENT_READ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getsPatientProfileWithOwnerAndMedicalRecordWithinAuthenticatedTenant() throws Exception {
        Fixture tenantA = fixture("profile-patients-a");
        Fixture tenantB = fixture("profile-patients-b");

        UUID ownerA = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO owners(id, tenant_id, first_name, last_name, document_type, document_number, phone, email, address, active)
                VALUES (?, ?, 'Valeria', 'Quispe', 'DNI', '44881234', '987654321', 'valeria@example.test', 'Av. Larco 450', true)
                """, ownerA, tenantA.tenant);

        UUID patientA = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients(id, tenant_id, owner_id, name, species, breed, sex, birth_date, color, microchip, sterilized, status)
                VALUES (?, ?, ?, 'Luna', 'CANINO', 'Mestizo', 'HEMBRA', '2023-04-10', 'Negro', 'CHIPSF1001', true, 'ACTIVO')
                """, patientA, tenantA.tenant, ownerA);

        UUID recordA = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medical_records(id, tenant_id, patient_id, allergies, medical_alerts, history)
                VALUES (?, ?, ?, 'Alergia a penicilina', 'Paciente braquicéfalo — monitoreo respiratorio', 'Control anual preventivo')
                """, recordA, tenantA.tenant, patientA);

        String profileJson = mvc.perform(get("/api/v1/patients/{patientId}", patientA)
                        .param("tenantId", tenantB.tenant.toString())
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patientA.toString()))
                .andExpect(jsonPath("$.name").value("Luna"))
                .andExpect(jsonPath("$.species").value("CANINO"))
                .andExpect(jsonPath("$.breed").value("Mestizo"))
                .andExpect(jsonPath("$.sex").value("HEMBRA"))
                .andExpect(jsonPath("$.birthDate").value("2023-04-10"))
                .andExpect(jsonPath("$.color").value("Negro"))
                .andExpect(jsonPath("$.microchip").value("CHIPSF1001"))
                .andExpect(jsonPath("$.sterilized").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVO"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.owner.id").value(ownerA.toString()))
                .andExpect(jsonPath("$.owner.fullName").value("Valeria Quispe"))
                .andExpect(jsonPath("$.owner.documentType").value("DNI"))
                .andExpect(jsonPath("$.owner.documentNumber").value("44881234"))
                .andExpect(jsonPath("$.owner.phone").value("987654321"))
                .andExpect(jsonPath("$.owner.email").value("valeria@example.test"))
                .andExpect(jsonPath("$.medicalRecord.id").value(recordA.toString()))
                .andExpect(jsonPath("$.medicalRecord.allergies").value("Alergia a penicilina"))
                .andExpect(jsonPath("$.medicalRecord.medicalAlerts").value("Paciente braquicéfalo — monitoreo respiratorio"))
                .andExpect(jsonPath("$.medicalRecord.history").value("Control anual preventivo"))
                .andExpect(jsonPath("$.medicalRecord.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(profileJson).doesNotContain("tenantId", "notificationsAccepted", "address");
    }

    @Test
    void enforcesProfileAuthenticationAuthorizationNotFoundAndTenantIsolation() throws Exception {
        Fixture tenantA = fixture("profile-iso-a");
        Fixture tenantB = fixture("profile-iso-b");

        UUID ownerB = insertOwner(tenantB.tenant, "Carlos", "Ajeno", "DNI", "70998811", "900111222");
        UUID patientB = insertPatientWithSensitiveRecord(
                tenantB.tenant, ownerB, "Thor Ajeno", "CANINO", "Labrador", "MACHO", "CHIP-B-999", "ACTIVO");

        // 401 without JWT
        mvc.perform(get("/api/v1/patients/{patientId}", patientB))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 403 without PATIENT_READ (e.g. ALMACEN with only DASHBOARD_VIEW)
        mvc.perform(get("/api/v1/patients/{patientId}", patientB)
                        .with(authentication(auth(tenantB, "DASHBOARD_VIEW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 404 for non-existent UUID
        mvc.perform(get("/api/v1/patients/{patientId}", UUID.randomUUID())
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el paciente solicitado."));

        // 404 for existing patient belonging to Tenant B when requested by Tenant A (uniform, no existence leak)
        mvc.perform(get("/api/v1/patients/{patientId}", patientB)
                        .with(authentication(auth(tenantA, "PATIENT_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el paciente solicitado."));
    }

    @Test
    void updatesPatientWithinTenantWithValidationOwnerReassignmentMicrochipConflictAndSafeAudit() throws Exception {
        Fixture tenantA = fixture("patient-upd-a");
        Fixture tenantB = fixture("patient-upd-b");
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(?)", Object.class, tenantA.tenant);

        // V10 RBAC verification for PATIENT_UPDATE
        for (String roleWithPerm : List.of("ADMINISTRADOR", "RECEPCIONISTA", "VETERINARIO")) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM role_permissions rp
                    JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND r.name = ? AND p.code = 'PATIENT_UPDATE'
                    """, Integer.class, tenantA.tenant, roleWithPerm);
            assertThat(count).as("Role %s should have PATIENT_UPDATE", roleWithPerm).isOne();
        }
        Integer almacenCount = jdbc.queryForObject("""
                SELECT count(*) FROM role_permissions rp
                JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.tenant_id = ? AND r.name = 'ALMACEN' AND p.code = 'PATIENT_UPDATE'
                """, Integer.class, tenantA.tenant);
        assertThat(almacenCount).as("ALMACEN should NOT have PATIENT_UPDATE").isZero();

        UUID ownerA1 = insertOwner(tenantA.tenant, "Valeria", "Quispe", "DNI", "44881234", "999111222");
        UUID ownerA2Active = insertOwner(tenantA.tenant, "Carlos", "Rojas", "DNI", "70112233", "988777666");
        UUID ownerA3Inactive = owner(tenantA, false);
        UUID ownerB1 = insertOwner(tenantB.tenant, "Elena", "Norte", "DNI", "99887766", "977666555");

        UUID patientA1 = insertPatientWithSensitiveRecord(
                tenantA.tenant, ownerA1, "Luna", "CANINO", "Mestizo", "HEMBRA", "CHIPA100", "ACTIVO");
        UUID patientA2 = insertPatientWithSensitiveRecord(
                tenantA.tenant, ownerA1, "Milo", "FELINO", "Siamés", "MACHO", "CHIPA200", "ACTIVO");
        UUID patientB1 = insertPatientWithSensitiveRecord(
                tenantB.tenant, ownerB1, "Max", "CANINO", "Pastor", "MACHO", "CHIPB300", "ACTIVO");

        String validUpdateBody = """
                {
                  "ownerId": "%s",
                  "name": "Luna Editada",
                  "species": "CANINO",
                  "breed": "Mestizo Mediano",
                  "sex": "HEMBRA",
                  "birthDate": "2022-08-15",
                  "color": "Blanco y negro",
                  "microchip": "chip-a-105",
                  "sterilized": true
                }
                """.formatted(ownerA2Active);

        // 401 without token & 403 without PATIENT_UPDATE
        mvc.perform(put("/api/v1/patients/{patientId}", patientA1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/patients/{patientId}", patientA1)
                        .with(authentication(auth(tenantA, "PATIENT_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 404 uniform for non-existent patient UUID and Tenant B patient
        mvc.perform(put("/api/v1/patients/{patientId}", UUID.fromString("00000000-0000-4000-8000-000000000999"))
                        .with(authentication(auth(tenantA, "PATIENT_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el paciente solicitado."));

        mvc.perform(put("/api/v1/patients/{patientId}", patientB1)
                        .with(authentication(auth(tenantA, "PATIENT_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el paciente solicitado."));

        // 404 when reassigning to inactive owner in Tenant A or active owner in Tenant B
        for (UUID invalidOwnerId : List.of(ownerA3Inactive, ownerB1, UUID.fromString("00000000-0000-4000-8000-000000000888"))) {
            String badOwnerBody = validUpdateBody.replace(ownerA2Active.toString(), invalidOwnerId.toString());
            mvc.perform(put("/api/v1/patients/{patientId}", patientA1)
                            .with(authentication(auth(tenantA, "PATIENT_UPDATE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badOwnerBody))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("No se encontró el propietario solicitado."));
        }

        // 409 conflict when microchip collides with patientA2 in the same tenant
        String microchipConflictBody = validUpdateBody.replace("chip-a-105", "CHIP-A-200");
        mvc.perform(put("/api/v1/patients/{patientId}", patientA1)
                        .with(authentication(auth(tenantA, "PATIENT_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(microchipConflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 200 OK update with reassignment to active ownerA2Active and normalized microchip
        UUID correlationId = UUID.randomUUID();
        mvc.perform(put("/api/v1/patients/{patientId}", patientA1)
                        .with(authentication(auth(tenantA, "PATIENT_UPDATE")))
                        .header("X-Correlation-ID", correlationId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(patientA1.toString()))
                .andExpect(jsonPath("$.ownerId").value(ownerA2Active.toString()))
                .andExpect(jsonPath("$.name").value("Luna Editada"))
                .andExpect(jsonPath("$.breed").value("Mestizo Mediano"))
                .andExpect(jsonPath("$.birthDate").value("2022-08-15"))
                .andExpect(jsonPath("$.microchip").value("CHIPA105"))
                .andExpect(jsonPath("$.sterilized").value(true));

        // Verify medical_records was NOT altered by patient update
        String allergiesAfter = jdbc.queryForObject(
                "SELECT allergies FROM medical_records WHERE tenant_id = ? AND patient_id = ?",
                String.class, tenantA.tenant, patientA1);
        assertThat(allergiesAfter).isEqualTo("ALERGIAS_SECRETAS");

        // Verify atomic PATIENT_UPDATED audit entry without sensitive PII or medical record content
        String auditJson = jdbc.queryForObject("""
                SELECT new_data::text FROM audit_entries
                WHERE tenant_id = ? AND entity_type = 'PATIENT' AND entity_id = ? AND action = 'PATIENT_UPDATED' AND correlation_id = ?
                """, String.class, tenantA.tenant, patientA1, correlationId);
        assertThat(auditJson)
                .contains("changedFields")
                .contains("ownerReassigned")
                .contains("Luna Editada")
                .doesNotContain("CHIPA105")
                .doesNotContain("2022-08-15")
                .doesNotContain("ALERGIAS_SECRETAS");
    }

    private Fixture fixture(String slug) {
        UUID t = UUID.randomUUID(), br = UUID.randomUUID(), u = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,name,slug,plan,status,created_at) VALUES(?,?,?,'STARTER','ACTIVO',now())",
                t, "Fictional clinic", slug + UUID.randomUUID().toString().substring(0, 6));
        jdbc.update("INSERT INTO branches(id,tenant_id,name,time_zone,active,created_at) VALUES(?,?,?,'America/Lima',true,now())",
                br, t, "Main");
        return new Fixture(t, br, u);
    }

    private UUID owner(Fixture f, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners(id,tenant_id,first_name,last_name,phone,active) VALUES(?,?, 'Owner','Fictional','999123456',?)",
                id, f.tenant, active);
        return id;
    }

    private UUID insertOwner(UUID tenantId, String firstName, String lastName, String docType, String docNumber, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO owners(id, tenant_id, first_name, last_name, document_type, document_number, phone, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, true)
                """, id, tenantId, firstName, lastName, docType, docNumber, phone);
        return id;
    }

    private UUID insertPatientWithSensitiveRecord(
            UUID tenantId,
            UUID ownerId,
            String name,
            String species,
            String breed,
            String sex,
            String microchip,
            String status) {
        UUID patientId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients(id, tenant_id, owner_id, name, species, breed, sex, microchip, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, patientId, tenantId, ownerId, name, species, breed, sex, microchip, status);
        jdbc.update("""
                INSERT INTO medical_records(id, tenant_id, patient_id, allergies, history, medical_alerts)
                VALUES (?, ?, ?, 'ALERGIAS_SECRETAS', 'HISTORIA_SECRETA', 'ALERTA_CRITICA_SECRETA')
                """, UUID.randomUUID(), tenantId, patientId);
        return patientId;
    }

    private UsernamePasswordAuthenticationToken auth(Fixture f, String... p) {
        var a = List.of(p).stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(new PetCarePrincipal(f.user, f.tenant, f.branch, a), null, a);
    }

    private String body(UUID owner) {
        return "{\"ownerId\":\"" + owner + "\",\"name\":\" Luna \",\"species\":\" Perro \",\"breed\":\" Mestizo \",\"sex\":\"hembra\",\"microchip\":\" chip-1 \",\"sterilized\":true}";
    }

    private void assertCount(String table, UUID tenant, int expected) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
        assertThat(count).isEqualTo(expected);
    }

    private record Fixture(UUID tenant, UUID branch, UUID user) { }
}
