package com.petcare.enterprise.identity.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RoleControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void enforcesRolePermissionsAndKeepsForeignRolesAndUsersInvisible() throws Exception {
        Fixture tenantA = fixture("roles-a");
        Fixture tenantB = fixture("roles-b");
        mockMvc.perform(get("/api/v1/roles")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/roles").with(authentication(auth(tenantA, "USER_READ"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/roles").with(authentication(auth(tenantA, "ROLE_READ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(4));
        mockMvc.perform(get("/api/v1/roles/" + tenantB.adminRole()).with(authentication(auth(tenantA, "ROLE_READ"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/users/" + tenantB.userId() + "/roles")
                        .with(authentication(auth(tenantA, "ROLE_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[],\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesRolePermissionsAndUserRolesWithAtomicAuditAndProtectsLastAdministrator() throws Exception {
        Fixture fixture = fixture("roles-update");
        UUID correlation = UUID.randomUUID();
        UUID roleAssignmentCorrelation = UUID.randomUUID();
        List<UUID> allPermissionIds = jdbc.query("SELECT id FROM permissions ORDER BY code", (row, index) -> row.getObject(1, UUID.class));
        mockMvc.perform(put("/api/v1/roles/" + fixture.veterinarianRole() + "/permissions")
                        .with(authentication(auth(fixture, "ROLE_MANAGE")))
                        .header("X-Correlation-ID", correlation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionIds", allPermissionIds))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(allPermissionIds.size()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND action = 'PERMISSIONS_UPDATED' AND correlation_id = ?",
                Integer.class, fixture.tenantId(), correlation)).isOne();

        mockMvc.perform(put("/api/v1/users/" + fixture.userId() + "/roles")
                        .with(authentication(auth(fixture, "ROLE_MANAGE")))
                        .header("X-Correlation-ID", roleAssignmentCorrelation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roleIds", List.of(fixture.veterinarianRole()), "version", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roleIds[0]").value(fixture.veterinarianRole().toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'ROLES_REPLACED' AND correlation_id = ?",
                Integer.class, fixture.tenantId(), fixture.userId(), roleAssignmentCorrelation)).isOne();
        assertThat(jdbc.queryForObject("SELECT new_data::text FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'ROLES_REPLACED'",
                String.class, fixture.tenantId(), fixture.userId())).doesNotContainIgnoringCase("password", "hash", "token");
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("clinicSlug", fixture.slug(), "email", fixture.userEmail(), "password", "Fictional-Password-123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identity.authorities").value(org.hamcrest.Matchers.hasItem("ROLE_MANAGE")));

        mockMvc.perform(put("/api/v1/users/" + fixture.actorId() + "/roles")
                        .with(authentication(auth(fixture, "ROLE_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("roleIds", List.of(fixture.veterinarianRole()), "version", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_CONFLICT"));
    }

    private Fixture fixture(String prefix) {
        UUID tenant = UUID.randomUUID(); UUID branch = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID user = UUID.randomUUID();
        String slug = prefix + "-" + tenant.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, slug, plan, status, created_at) VALUES (?, ?, ?, 'STARTER', 'ACTIVO', now())",
                tenant, "Fictional Clinic", slug);
        jdbc.update("INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at) VALUES (?, ?, 'Main', 'America/Lima', true, now())", branch, tenant);
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(?)", Object.class, tenant);
        UUID admin = role(tenant, "ADMINISTRADOR"); UUID vet = role(tenant, "VETERINARIO");
        String userEmail = "user-" + user + "@example.test";
        insertUser(tenant, actor, "admin-" + actor + "@example.test"); insertUser(tenant, user, userEmail);
        for (UUID id : List.of(actor, user)) jdbc.update("INSERT INTO user_branches (tenant_id,user_id,branch_id,primary_branch,assigned_at) VALUES (?,?,?,true,now())", tenant, id, branch);
        jdbc.update("INSERT INTO user_roles (tenant_id,user_id,role_id,assigned_at) VALUES (?,?,?,now())", tenant, actor, admin);
        jdbc.update("INSERT INTO user_roles (tenant_id,user_id,role_id,assigned_at) VALUES (?,?,?,now())", tenant, user, admin);
        return new Fixture(tenant, branch, actor, user, admin, vet, slug, userEmail);
    }

    private UUID role(UUID tenant, String name) { return jdbc.queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND name = ?", UUID.class, tenant, name); }
    private void insertUser(UUID tenant, UUID id, String email) { jdbc.update("INSERT INTO users (id,tenant_id,first_name,last_name,email,password_hash,status,created_at,updated_at,version) VALUES (?,?, 'User','Fictional',?,?,'ACTIVO',now(),now(),0)", id, tenant, email, passwordEncoder.encode("Fictional-Password-123!")); }
    private UsernamePasswordAuthenticationToken auth(Fixture fixture, String... permissions) {
        var authorities = java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
        var principal = new PetCarePrincipal(fixture.actorId(), fixture.tenantId(), fixture.branchId(), authorities);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
    private String json(Object value) throws Exception { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
    private record Fixture(UUID tenantId, UUID branchId, UUID actorId, UUID userId, UUID adminRole, UUID veterinarianRole, String slug, String userEmail) { }
}
