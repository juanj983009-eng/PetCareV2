package com.petcare.enterprise.clinical.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.UUID;
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
class OwnerControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void searchesPartiallyWithPaginationAndNeverCrossesTenantBoundary() throws Exception {
        Fixture tenantA = fixture("owners-a");
        Fixture tenantB = fixture("owners-b");
        owner(tenantA.tenantId(), "María", "López", "DNI", "74210001", "999111222");
        owner(tenantA.tenantId(), "Mario", "Torres", "DNI", "74210002", "999111333");
        owner(tenantB.tenantId(), "María", "Ajena", "DNI", "74210001", "988444555");
        mockMvc.perform(get("/api/v1/owners?query=mar&page=0&size=1&sortBy=name&direction=asc")
                        .with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/owners?query=2100").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/api/v1/owners?query=1113").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lastName").value("Torres"));
        mockMvc.perform(get("/api/v1/owners?query=ajena").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void requiresPermissionAndRejectsUnsafeQuery() throws Exception {
        Fixture fixture = fixture("owners-security");
        mockMvc.perform(get("/api/v1/owners?query=ma")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/owners?query=ma").with(authentication(auth(fixture, "USER_READ"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/owners?query=x").with(authentication(auth(fixture, "OWNER_READ"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/owners").param("query", "  ").with(authentication(auth(fixture, "OWNER_READ"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesOwnerWithinTenantWithValidationConflictsPhoneConfirmationAndSafeAudit() throws Exception {
        Fixture tenantA = fixture("owners-upd-a");
        Fixture tenantB = fixture("owners-upd-b");

        UUID ownerA1 = ownerWithId(tenantA.tenantId(), "Valeria", "Quispe", "DNI", "44881234", "999111222");
        UUID ownerA2 = ownerWithId(tenantA.tenantId(), "Carlos", "Rojas", "DNI", "70112233", "988777666");
        UUID ownerB1 = ownerWithId(tenantB.tenantId(), "Elena", "Norte", "DNI", "99887766", "977666555");

        // V10 RBAC verification for OWNER_UPDATE
        for (String roleWithPerm : List.of("ADMINISTRADOR", "RECEPCIONISTA")) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM role_permissions rp
                    JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND r.name = ? AND p.code = 'OWNER_UPDATE'
                    """, Integer.class, tenantA.tenantId(), roleWithPerm);
            assertThat(count).as("Role %s should have OWNER_UPDATE", roleWithPerm).isOne();
        }
        for (String roleWithoutPerm : List.of("VETERINARIO", "ALMACEN")) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM role_permissions rp
                    JOIN roles r ON r.tenant_id = rp.tenant_id AND r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.tenant_id = ? AND r.name = ? AND p.code = 'OWNER_UPDATE'
                    """, Integer.class, tenantA.tenantId(), roleWithoutPerm);
            assertThat(count).as("Role %s should NOT have OWNER_UPDATE", roleWithoutPerm).isZero();
        }

        String validBody = """
                {
                  "firstName": "Valeria Sofía",
                  "lastName": "Quispe Prado",
                  "documentType": "DNI",
                  "documentNumber": "4488-1234",
                  "phone": "999 111 229",
                  "email": "Valeria.Sofia@PetCare.pe",
                  "address": "Av. Arequipa 1500",
                  "notificationsAccepted": true,
                  "confirmPhoneDuplicate": false
                }
                """;

        // 401 without token & 403 without OWNER_UPDATE
        mockMvc.perform(put("/api/v1/owners/" + ownerA1).contentType(MediaType.APPLICATION_JSON).content(validBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/owners/" + ownerA1)
                        .with(authentication(auth(tenantA, "OWNER_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 404 uniform for non-existent UUID and Tenant B owner
        UUID missingId = UUID.fromString("00000000-0000-4000-8000-000000000999");
        mockMvc.perform(put("/api/v1/owners/" + missingId)
                        .with(authentication(auth(tenantA, "OWNER_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el propietario solicitado."));

        mockMvc.perform(put("/api/v1/owners/" + ownerB1)
                        .with(authentication(auth(tenantA, "OWNER_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el propietario solicitado."));

        // Document conflict against ownerA2 (409 CONFLICT)
        String docConflictBody = """
                {
                  "firstName": "Valeria",
                  "lastName": "Quispe",
                  "documentType": "DNI",
                  "documentNumber": "70112233",
                  "phone": "999111222",
                  "notificationsAccepted": true,
                  "confirmPhoneDuplicate": false
                }
                """;
        mockMvc.perform(put("/api/v1/owners/" + ownerA1)
                        .with(authentication(auth(tenantA, "OWNER_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(docConflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // Phone duplicate warning against ownerA2 (409 when confirmPhoneDuplicate=false, 200 when true)
        String phoneDupWarningBody = """
                {
                  "firstName": "Valeria Sofía",
                  "lastName": "Quispe Prado",
                  "documentType": "DNI",
                  "documentNumber": "44881234",
                  "phone": "988777666",
                  "email": "valeria.sofia@petcare.pe",
                  "address": "Av. Arequipa 1500",
                  "notificationsAccepted": true,
                  "confirmPhoneDuplicate": false
                }
                """;
        mockMvc.perform(put("/api/v1/owners/" + ownerA1)
                        .with(authentication(auth(tenantA, "OWNER_UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phoneDupWarningBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        UUID correlationId = UUID.randomUUID();
        String phoneDupConfirmedBody = phoneDupWarningBody.replace("\"confirmPhoneDuplicate\": false", "\"confirmPhoneDuplicate\": true");
        mockMvc.perform(put("/api/v1/owners/" + ownerA1)
                        .with(authentication(auth(tenantA, "OWNER_UPDATE")))
                        .header("X-Correlation-ID", correlationId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(phoneDupConfirmedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownerA1.toString()))
                .andExpect(jsonPath("$.firstName").value("Valeria Sofía"))
                .andExpect(jsonPath("$.lastName").value("Quispe Prado"))
                .andExpect(jsonPath("$.documentNumber").value("44881234"))
                .andExpect(jsonPath("$.phone").value("988777666"))
                .andExpect(jsonPath("$.email").value("valeria.sofia@petcare.pe"))
                .andExpect(jsonPath("$.notificationsAccepted").value(true));

        // Verify GET /api/v1/owners returns the deliberately expanded DTO for edit preloading without exposing tenantId or internal fields
        mockMvc.perform(get("/api/v1/owners?query=Valeria")
                        .with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownerA1.toString()))
                .andExpect(jsonPath("$.content[0].firstName").value("Valeria Sofía"))
                .andExpect(jsonPath("$.content[0].lastName").value("Quispe Prado"))
                .andExpect(jsonPath("$.content[0].documentType").value("DNI"))
                .andExpect(jsonPath("$.content[0].documentNumber").value("44881234"))
                .andExpect(jsonPath("$.content[0].phone").value("988777666"))
                .andExpect(jsonPath("$.content[0].email").value("valeria.sofia@petcare.pe"))
                .andExpect(jsonPath("$.content[0].address").value("Av. Arequipa 1500"))
                .andExpect(jsonPath("$.content[0].notificationsAccepted").value(true))
                .andExpect(jsonPath("$.content[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.content[0].active").doesNotExist())
                .andExpect(jsonPath("$.content[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$.content[0].updatedAt").doesNotExist());

        // Verify atomic OWNER_UPDATED audit entry without sensitive PII
        String auditJson = jdbc.queryForObject("""
                SELECT new_data::text FROM audit_entries
                WHERE tenant_id = ? AND entity_type = 'OWNER' AND entity_id = ? AND action = 'OWNER_UPDATED' AND correlation_id = ?
                """, String.class, tenantA.tenantId(), ownerA1, correlationId);
        assertThat(auditJson)
                .contains("changedFields")
                .contains("firstName")
                .doesNotContain("988777666")
                .doesNotContain("44881234")
                .doesNotContain("valeria.sofia@petcare.pe")
                .doesNotContain("Av. Arequipa 1500");
    }

    private Fixture fixture(String prefix) {
        UUID tenant = UUID.randomUUID(), branch = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id,name,slug,plan,status,created_at) VALUES (?,?,?,'STARTER','ACTIVO',now())", tenant, "Fictional clinic", prefix + tenant.toString().substring(0, 8));
        jdbc.update("INSERT INTO branches (id,tenant_id,name,time_zone,active,created_at) VALUES (?,?,'Main','America/Lima',true,now())", branch, tenant);
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(?)", Object.class, tenant);
        return new Fixture(tenant, branch, user);
    }

    private void owner(UUID tenant, String first, String last, String type, String document, String phone) {
        ownerWithId(tenant, first, last, type, document, phone);
    }

    private UUID ownerWithId(UUID tenant, String first, String last, String type, String document, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id,tenant_id,first_name,last_name,document_type,document_number,phone,active) VALUES (?,?,?,?,?,?,?,true)", id, tenant, first, last, type, document, phone);
        return id;
    }

    private UsernamePasswordAuthenticationToken auth(Fixture fixture, String... permissions) {
        var authorities = List.of(permissions).stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(new PetCarePrincipal(fixture.userId(), fixture.tenantId(), fixture.branchId(), authorities), null, authorities);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID userId) { }
}
