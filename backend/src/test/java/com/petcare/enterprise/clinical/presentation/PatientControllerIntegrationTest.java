package com.petcare.enterprise.clinical.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
