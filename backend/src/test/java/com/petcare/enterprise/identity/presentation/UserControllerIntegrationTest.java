package com.petcare.enterprise.identity.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.identity.infrastructure.security.PetCarePrincipal;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String USERS = "/api/v1/users";
    private static final String PASSWORD = "Fictional-Password-123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @SpyBean private AuditLogWriter auditLogWriter;

    @Test
    void enforcesAuthenticationAndReadManageMatrix() throws Exception {
        Fixture fixture = fixture("matrix");

        mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(USERS).with(authentication(auth(fixture, "OTHER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS).with(authentication(auth(fixture, "USER_READ"))))
                .andExpect(status().isOk());
        mockMvc.perform(post(USERS)
                        .with(authentication(auth(fixture, "USER_READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest(fixture, "read-only@example.test"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(USERS)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest(fixture, "manager@example.test"))))
                .andExpect(status().isCreated());
        Map<String, Object> invalid = createRequest(fixture, "invalid@example.test");
        invalid.remove("firstName");
        mockMvc.perform(post(USERS)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("firstName"));
    }

    @Test
    void createsListsAndReadsTenantUserWithoutExposingHash() throws Exception {
        Fixture fixture = fixture("create");
        UUID correlationId = UUID.randomUUID();
        MvcResult result = mockMvc.perform(post(USERS)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest(fixture, "New.User@Example.Test"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(jsonPath("$.email").value("new.user@example.test"))
                .andExpect(jsonPath("$.status").value("ACTIVO"))
                .andExpect(jsonPath("$.role.id").value(fixture.roleId().toString()))
                .andExpect(jsonPath("$.branch.id").value(fixture.branchId().toString()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.initialPassword").doesNotExist())
                .andReturn();
        UUID userId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());

        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'CREATE' AND correlation_id = ?",
                Integer.class, fixture.tenantId(), userId, correlationId)).isOne();
        String audit = jdbcTemplate.queryForObject(
                "SELECT new_data::text FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action = 'CREATE'",
                String.class, fixture.tenantId(), userId);
        assertThat(audit).doesNotContainIgnoringCase("password", "token", "hash");

        mockMvc.perform(get(USERS)
                        .with(authentication(auth(fixture, "USER_READ")))
                        .param("search", "new.user")
                        .param("roleId", fixture.roleId().toString())
                        .param("status", "ACTIVO")
                        .param("sortBy", "email")
                        .param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        mockMvc.perform(get(USERS + "/" + userId)
                        .with(authentication(auth(fixture, "USER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void emailIsUniquePerTenantAndForeignResourcesAreInvisible() throws Exception {
        Fixture tenantA = fixture("tenant-a");
        Fixture tenantB = fixture("tenant-b");
        String shared = "shared@example.test";
        UUID userA = create(tenantA, shared);
        UUID userB = create(tenantB, shared);
        assertThat(userA).isNotEqualTo(userB);

        mockMvc.perform(post(USERS)
                        .with(authentication(auth(tenantA, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest(tenantA, shared))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
        mockMvc.perform(get(USERS + "/" + userB)
                        .with(authentication(auth(tenantA, "USER_READ"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        Map<String, Object> foreignRole = createRequest(tenantA, "foreign-role@example.test");
        foreignRole.put("roleId", tenantB.roleId());
        mockMvc.perform(post(USERS)
                        .with(authentication(auth(tenantA, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(foreignRole)))
                .andExpect(status().isNotFound());
        Map<String, Object> foreignBranch = createRequest(tenantA, "foreign-branch@example.test");
        foreignBranch.put("branchId", tenantB.branchId());
        mockMvc.perform(post(USERS)
                        .with(authentication(auth(tenantA, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(foreignBranch)))
                .andExpect(status().isNotFound());
    }

    @Test
    void paginatesFiltersSortsAndKeepsTenantAwareAssociations() throws Exception {
        Fixture fixture = fixture("page");
        UUID first = create(fixture, "page-a@example.test");
        create(fixture, "page-b@example.test");
        create(fixture, "page-c@example.test");

        mockMvc.perform(get(USERS)
                        .with(authentication(auth(fixture, "USER_READ")))
                        .param("search", "page-")
                        .param("roleId", fixture.roleId().toString())
                        .param("status", "ACTIVO")
                        .param("sortBy", "email")
                        .param("direction", "desc")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value("page-a@example.test"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_roles WHERE tenant_id = ? AND user_id = ? AND role_id = ?",
                Integer.class, fixture.tenantId(), first, fixture.roleId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_branches WHERE tenant_id = ? AND user_id = ? AND branch_id = ? AND primary_branch",
                Integer.class, fixture.tenantId(), first, fixture.branchId())).isOne();
    }

    @Test
    void editsRoleAndBranchWithAuditAndRejectsStaleOrHostileRequests() throws Exception {
        Fixture fixture = fixture("edit");
        UUID userId = create(fixture, "edit@example.test");
        UUID secondRole = insertRole(fixture.tenantId(), "VETERINARIO", false);
        UUID secondBranch = insertBranch(fixture.tenantId(), "Sede Dos");
        UUID correlationId = UUID.randomUUID();

        Map<String, Object> update = Map.of(
                "firstName", "Editado",
                "lastName", "Seguro",
                "email", "edited@example.test",
                "roleId", secondRole,
                "branchId", secondBranch,
                "version", 0);
        mockMvc.perform(put(USERS + "/" + userId)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.role.id").value(secondRole.toString()))
                .andExpect(jsonPath("$.branch.id").value(secondBranch.toString()));
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND correlation_id = ? ORDER BY occurred_at, action",
                String.class, fixture.tenantId(), userId, correlationId))
                .containsExactlyInAnyOrder("UPDATE", "ROLE_CHANGED", "BRANCH_CHANGED");

        mockMvc.perform(put(USERS + "/" + userId)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_CONFLICT"));

        Map<String, Object> hostile = new java.util.LinkedHashMap<>(update);
        hostile.put("version", 1);
        hostile.put("tenantId", UUID.randomUUID());
        hostile.put("authorities", List.of("USER_MANAGE"));
        mockMvc.perform(put(USERS + "/" + userId)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(hostile)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void deactivationBlocksLoginAndActivationRestoresItWithoutDeletingHistory() throws Exception {
        Fixture fixture = fixture("status");
        UUID userId = create(fixture, "status@example.test");

        mockMvc.perform(post(USERS + "/" + userId + "/deactivate")
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVO"))
                .andExpect(jsonPath("$.version").value(1));
        login(fixture.slug(), "status@example.test", PASSWORD).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId)).isOne();

        mockMvc.perform(post(USERS + "/" + userId + "/activate")
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVO"))
                .andExpect(jsonPath("$.version").value(2));
        login(fixture.slug(), "status@example.test", PASSWORD).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM audit_entries WHERE tenant_id = ? AND entity_id = ? AND action IN ('ACTIVATE','DEACTIVATE')",
                String.class, fixture.tenantId(), userId))
                .containsExactlyInAnyOrder("ACTIVATE", "DEACTIVATE");

        mockMvc.perform(post(USERS + "/" + userId + "/activate")
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void auditFailureRollsBackUserAndAssociations() throws Exception {
        Fixture fixture = fixture("rollback");
        String email = "rollback@example.test";
        doThrow(new IllegalStateException("Fictional audit failure")).when(auditLogWriter).append(any());
        try {
            mockMvc.perform(post(USERS)
                            .with(authentication(auth(fixture, "USER_MANAGE")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(createRequest(fixture, email))))
                    .andExpect(status().isInternalServerError());
        } finally {
            reset(auditLogWriter);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE tenant_id = ? AND email = ?",
                Integer.class, fixture.tenantId(), email)).isZero();
    }

    private UUID create(Fixture fixture, String email) throws Exception {
        MvcResult result = mockMvc.perform(post(USERS)
                        .with(authentication(auth(fixture, "USER_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(createRequest(fixture, email))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions login(String slug, String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("clinicSlug", slug, "email", email, "password", password))));
    }

    private Fixture fixture(String prefix) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = insertBranchWithTenant(tenantId, prefix);
        UUID roleId = insertRole(tenantId, "ADMINISTRADOR", true);
        UUID actorId = UUID.randomUUID();
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                UPDATE tenants SET slug = ? WHERE id = ?
                """, slug, tenantId);
        jdbcTemplate.update("""
                INSERT INTO users (
                    id, tenant_id, first_name, last_name, email, password_hash,
                    status, created_at, updated_at, version
                ) VALUES (?, ?, 'Admin', 'Ficticio', ?, ?, 'ACTIVO', now(), now(), 0)
                """, actorId, tenantId, "admin-" + actorId + "@example.test", passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, actorId, roleId);
        jdbcTemplate.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantId, actorId, branchId);
        assignPermission(tenantId, roleId, "USER_READ");
        assignPermission(tenantId, roleId, "USER_MANAGE");
        return new Fixture(tenantId, branchId, roleId, actorId, slug);
    }

    private UUID insertBranchWithTenant(UUID tenantId, String prefix) {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, 'Fictional Clinic', ?, 'STARTER', 'ACTIVO', now())
                """, tenantId, prefix + "-temp-" + UUID.randomUUID().toString().substring(0, 8));
        return insertBranch(tenantId, "Principal");
    }

    private UUID insertBranch(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, ?, 'America/Lima', true, now())
                """, id, tenantId, name + " " + id.toString().substring(0, 8));
        return id;
    }

    private UUID insertRole(UUID tenantId, String name, boolean system) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, ?, ?, now())
                """, id, tenantId, name, system);
        return id;
    }

    private void assignPermission(UUID tenantId, UUID roleId, String code) {
        jdbcTemplate.update("""
                INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                SELECT ?, ?, id, now() FROM permissions WHERE code = ?
                ON CONFLICT DO NOTHING
                """, tenantId, roleId, code);
    }

    private UsernamePasswordAuthenticationToken auth(Fixture fixture, String... authorities) {
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        PetCarePrincipal principal = new PetCarePrincipal(
                fixture.actorId(), fixture.tenantId(), fixture.branchId(), granted);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, granted);
    }

    private static Map<String, Object> createRequest(Fixture fixture, String email) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("firstName", "Nuevo");
        request.put("lastName", "Usuario");
        request.put("email", email);
        request.put("initialPassword", PASSWORD);
        request.put("roleId", fixture.roleId());
        request.put("branchId", fixture.branchId());
        return request;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID roleId, UUID actorId, String slug) {
    }
}
