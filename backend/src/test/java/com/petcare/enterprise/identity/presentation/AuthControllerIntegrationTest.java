package com.petcare.enterprise.identity.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.identity.infrastructure.security.JwtProperties;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String SESSION = "/api/v1/auth/session";
    private static final String PASSWORD = "Fictional-Password-123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void validLoginReturnsVerifiedJwtRebuildsSessionAndPersistsCorrelatedEvent() throws Exception {
        Fixture fixture = fixture("valid", "ACTIVO", "ACTIVO", "admin@example.test");
        UUID correlationId = UUID.randomUUID();

        MvcResult result = login(fixture.slug(), fixture.email(), PASSWORD, correlationId)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.identity.userId").value(fixture.userId().toString()))
                .andExpect(jsonPath("$.identity.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.identity.branchId").value(fixture.branchId().toString()))
                .andExpect(jsonPath("$.identity.authorities[0]").value(fixture.authority()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("accessToken").asText();
        var jwt = jwtDecoder.decode(token);
        assertThat(jwt.getIssuer().toString()).isEqualTo(jwtProperties.issuer());
        assertThat(jwt.getSubject()).isEqualTo(fixture.userId().toString());
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(jwt.getClaimAsString("uid")).isEqualTo(fixture.userId().toString());
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(fixture.tenantId().toString());
        assertThat(jwt.getClaimAsString("bid")).isEqualTo(fixture.branchId().toString());
        assertThat(jwt.getClaimAsStringList("authorities")).containsExactly(fixture.authority());

        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixture.userId().toString()))
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.branchId().toString()))
                .andExpect(jsonPath("$.profile.displayName").value("Alex Fictional"))
                .andExpect(jsonPath("$.profile.email").value(fixture.email()))
                .andExpect(jsonPath("$.profile.roles[0]").value("ADMINISTRADOR"))
                .andExpect(jsonPath("$.profile.clinicName").value("Fictional Clinic"))
                .andExpect(jsonPath("$.profile.branchName").value("Main Branch"));

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM authentication_events
                WHERE tenant_id = ? AND user_id = ? AND result = 'SUCCESS'
                  AND internal_reason = 'AUTHENTICATED' AND correlation_id = ?
                """,
                Integer.class,
                fixture.tenantId(), fixture.userId(), correlationId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_access_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class,
                fixture.userId())).isTrue();
    }

    @Test
    void allCredentialFailuresUseSameExternalContractAndPersistIndependentEvents() throws Exception {
        Fixture active = fixture("generic", "ACTIVO", "ACTIVO", "generic@example.test");
        Fixture inactiveTenant = fixture("inactive-tenant", "SUSPENDIDO", "ACTIVO", "inactive@example.test");
        Fixture inactiveUser = fixture("inactive-user", "ACTIVO", "INACTIVO", "inactive@example.test");
        Fixture blockedUser = fixture("blocked-user", "ACTIVO", "BLOQUEADO", "blocked@example.test");

        List<Attempt> attempts = List.of(
                new Attempt("missing-" + UUID.randomUUID().toString().substring(0, 8), active.email(), PASSWORD),
                new Attempt(active.slug(), "missing@example.test", PASSWORD),
                new Attempt(active.slug(), active.email(), "Wrong-Password-123!"),
                new Attempt(inactiveTenant.slug(), inactiveTenant.email(), PASSWORD),
                new Attempt(inactiveUser.slug(), inactiveUser.email(), PASSWORD),
                new Attempt(blockedUser.slug(), blockedUser.email(), PASSWORD));

        for (Attempt attempt : attempts) {
            MvcResult result = login(attempt.slug(), attempt.email(), attempt.password(), UUID.randomUUID())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("Las credenciales proporcionadas no son válidas."))
                    .andExpect(jsonPath("$.fieldErrors").isEmpty())
                    .andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeader("X-Correlation-ID"))
                    .isEqualTo(body.get("correlationId").asText());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM authentication_events WHERE result = 'FAILURE'",
                Integer.class)).isGreaterThanOrEqualTo(attempts.size());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM authentication_events
                WHERE internal_reason = 'TENANT_NOT_FOUND' AND tenant_id IS NULL AND user_id IS NULL
                """,
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void validationAndUnknownClientIdentityFieldsAreRejectedUniformly() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clinicSlug":"Invalid_Slug","email":"bad","password":"",
                                 "tenantId":"00000000-0000-0000-0000-000000000000"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void sameEmailInDifferentTenantsProducesIsolatedTenantClaims() throws Exception {
        String email = "shared@example.test";
        Fixture tenantA = fixture("shared-a", "ACTIVO", "ACTIVO", email);
        Fixture tenantB = fixture("shared-b", "ACTIVO", "ACTIVO", email);

        String tokenA = token(login(tenantA.slug(), email, PASSWORD, UUID.randomUUID()).andReturn());
        String tokenB = token(login(tenantB.slug(), email, PASSWORD, UUID.randomUUID()).andReturn());

        assertThat(jwtDecoder.decode(tokenA).getClaimAsString("tid")).isEqualTo(tenantA.tenantId().toString());
        assertThat(jwtDecoder.decode(tokenB).getClaimAsString("tid")).isEqualTo(tenantB.tenantId().toString());
        assertThat(jwtDecoder.decode(tokenA).getClaimAsString("uid")).isNotEqualTo(
                jwtDecoder.decode(tokenB).getClaimAsString("uid"));
    }

    @Test
    void manipulatedAndExpiredTokensReceiveUniformUnauthorized() throws Exception {
        Fixture fixture = fixture("tokens", "ACTIVO", "ACTIVO", "tokens@example.test");
        String valid = token(login(fixture.slug(), fixture.email(), PASSWORD, UUID.randomUUID()).andReturn());
        int position = valid.length() - 8;
        char replacement = valid.charAt(position) == 'a' ? 'b' : 'a';
        String manipulated = valid.substring(0, position) + replacement + valid.substring(position + 1);

        assertUnauthorized(manipulated);
        assertUnauthorized(expiredToken(fixture));
        mockMvc.perform(get(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tenantTokenCannotUsePlatformAuthority() throws Exception {
        Fixture fixture = fixture("forbidden", "ACTIVO", "ACTIVO", "forbidden@example.test");
        String token = token(login(fixture.slug(), fixture.email(), PASSWORD, UUID.randomUUID()).andReturn());

        mockMvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void sessionReturnsProfileWithoutBranchWhenUnassigned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String slug = "nobranch-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "vet@example.test";

        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, 'Clínica Sin Sede', ?, 'STARTER', 'ACTIVO', now())
                """, tenantId, slug);
        jdbcTemplate.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at)
                VALUES (?, ?, 'Valeria', 'Rios', ?, ?, 'ACTIVO', now())
                """, userId, tenantId, email, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'VETERINARIO', true, now())
                """, roleId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, userId, roleId);

        String token = token(login(slug, email, PASSWORD, UUID.randomUUID()).andReturn());

        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.branchId").doesNotExist())
                .andExpect(jsonPath("$.profile.displayName").value("Valeria Rios"))
                .andExpect(jsonPath("$.profile.email").value(email))
                .andExpect(jsonPath("$.profile.roles[0]").value("VETERINARIO"))
                .andExpect(jsonPath("$.profile.clinicName").value("Clínica Sin Sede"))
                .andExpect(jsonPath("$.profile.branchName").doesNotExist());
    }

    @Test
    void sessionCanBeQueriedByNonAdminUserWithoutUserReadOrManagePermissions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        String slug = "reception-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "receptionist@example.test";

        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, 'Clínica Las Flores', ?, 'STARTER', 'ACTIVO', now())
                """, tenantId, slug);
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, 'Sede Sur', 'America/Lima', true, now())
                """, branchId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at)
                VALUES (?, ?, 'Renato', 'Vargas', ?, ?, 'ACTIVO', now())
                """, userId, tenantId, email, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'RECEPCIONISTA', false, now())
                """, roleId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, userId, roleId);
        jdbcTemplate.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantId, userId, branchId);

        String token = token(login(slug, email, PASSWORD, UUID.randomUUID()).andReturn());

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.displayName").value("Renato Vargas"))
                .andExpect(jsonPath("$.profile.email").value(email))
                .andExpect(jsonPath("$.profile.roles[0]").value("RECEPCIONISTA"))
                .andExpect(jsonPath("$.profile.clinicName").value("Clínica Las Flores"))
                .andExpect(jsonPath("$.profile.branchName").value("Sede Sur"));
    }

    @Test
    void sessionProfileEnforcesStrictTenantIsolationBetweenDistinctTenants() throws Exception {
        // Tenant A: Clínica Los Olivos
        UUID tenantAId = UUID.randomUUID();
        UUID branchAId = UUID.randomUUID();
        UUID userAId = UUID.randomUUID();
        UUID roleA1Id = UUID.randomUUID();
        UUID roleA2Id = UUID.randomUUID();
        String slugA = "olivos-" + UUID.randomUUID().toString().substring(0, 8);
        String emailA = "carlos.mendoza@olivos.test";
        String clinicNameA = "Clínica Veterinaria Los Olivos";
        String branchNameA = "Sede Central Olivos";

        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, ?, ?, 'STARTER', 'ACTIVO', now())
                """, tenantAId, clinicNameA, slugA);
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, ?, 'America/Lima', true, now())
                """, branchAId, tenantAId, branchNameA);
        jdbcTemplate.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at)
                VALUES (?, ?, 'Carlos', 'Mendoza', ?, ?, 'ACTIVO', now())
                """, userAId, tenantAId, emailA, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'ADMINISTRADOR', true, now())
                """, roleA1Id, tenantAId);
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'VETERINARIO', true, now())
                """, roleA2Id, tenantAId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantAId, userAId, roleA1Id);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantAId, userAId, roleA2Id);
        jdbcTemplate.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantAId, userAId, branchAId);

        // Tenant B: Clínica Miraflores
        UUID tenantBId = UUID.randomUUID();
        UUID branchBId = UUID.randomUUID();
        UUID userBId = UUID.randomUUID();
        UUID roleBId = UUID.randomUUID();
        String slugB = "miraflores-" + UUID.randomUUID().toString().substring(0, 8);
        String emailB = "beatriz.silva@miraflores.test";
        String clinicNameB = "Clínica Veterinaria Miraflores";
        String branchNameB = "Sede Costanera";

        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, ?, ?, 'PROFESSIONAL', 'ACTIVO', now())
                """, tenantBId, clinicNameB, slugB);
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, ?, 'America/Lima', true, now())
                """, branchBId, tenantBId, branchNameB);
        jdbcTemplate.update("""
                INSERT INTO users (id, tenant_id, first_name, last_name, email, password_hash, status, created_at)
                VALUES (?, ?, 'Beatriz', 'Silva', ?, ?, 'ACTIVO', now())
                """, userBId, tenantBId, emailB, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'RECEPCIONISTA', false, now())
                """, roleBId, tenantBId);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantBId, userBId, roleBId);
        jdbcTemplate.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantBId, userBId, branchBId);

        String tokenA = token(login(slugA, emailA, PASSWORD, UUID.randomUUID()).andReturn());
        String tokenB = token(login(slugB, emailB, PASSWORD, UUID.randomUUID()).andReturn());

        // Comprobación Tenant A: resuelve su propio perfil y no filtra datos de Tenant B
        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userAId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantAId.toString()))
                .andExpect(jsonPath("$.branchId").value(branchAId.toString()))
                .andExpect(jsonPath("$.profile.displayName").value("Carlos Mendoza"))
                .andExpect(jsonPath("$.profile.email").value(emailA))
                .andExpect(jsonPath("$.profile.roles").value(org.hamcrest.Matchers.containsInAnyOrder("ADMINISTRADOR", "VETERINARIO")))
                .andExpect(jsonPath("$.profile.clinicName").value(clinicNameA))
                .andExpect(jsonPath("$.profile.branchName").value(branchNameA))
                .andExpect(jsonPath("$.profile.displayName").value(org.hamcrest.Matchers.not("Beatriz Silva")))
                .andExpect(jsonPath("$.profile.email").value(org.hamcrest.Matchers.not(emailB)))
                .andExpect(jsonPath("$.profile.clinicName").value(org.hamcrest.Matchers.not(clinicNameB)))
                .andExpect(jsonPath("$.profile.branchName").value(org.hamcrest.Matchers.not(branchNameB)))
                .andExpect(jsonPath("$.profile.roles").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("RECEPCIONISTA"))));

        // Comprobación Tenant B: resuelve su propio perfil y no filtra datos de Tenant A
        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userBId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantBId.toString()))
                .andExpect(jsonPath("$.branchId").value(branchBId.toString()))
                .andExpect(jsonPath("$.profile.displayName").value("Beatriz Silva"))
                .andExpect(jsonPath("$.profile.email").value(emailB))
                .andExpect(jsonPath("$.profile.roles").value(org.hamcrest.Matchers.contains("RECEPCIONISTA")))
                .andExpect(jsonPath("$.profile.clinicName").value(clinicNameB))
                .andExpect(jsonPath("$.profile.branchName").value(branchNameB))
                .andExpect(jsonPath("$.profile.displayName").value(org.hamcrest.Matchers.not("Carlos Mendoza")))
                .andExpect(jsonPath("$.profile.email").value(org.hamcrest.Matchers.not(emailA)))
                .andExpect(jsonPath("$.profile.clinicName").value(org.hamcrest.Matchers.not(clinicNameA)))
                .andExpect(jsonPath("$.profile.branchName").value(org.hamcrest.Matchers.not(branchNameA)))
                .andExpect(jsonPath("$.profile.roles").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ADMINISTRADOR"))))
                .andExpect(jsonPath("$.profile.roles").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("VETERINARIO"))));
    }

    private void assertUnauthorized(String token) throws Exception {
        mockMvc.perform(get(SESSION).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Debes autenticarte para continuar."));
    }

    private String expiredToken(Fixture fixture) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(fixture.userId().toString())
                .issuedAt(now.minusSeconds(120))
                .expiresAt(now.minusSeconds(60))
                .id(UUID.randomUUID().toString())
                .claim("uid", fixture.userId().toString())
                .claim("tid", fixture.tenantId().toString())
                .claim("bid", fixture.branchId().toString())
                .claim("authorities", List.of(fixture.authority()))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private org.springframework.test.web.servlet.ResultActions login(
            String slug,
            String email,
            String password,
            UUID correlationId) throws Exception {
        return mockMvc.perform(post(LOGIN)
                .header("X-Correlation-ID", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "clinicSlug", slug,
                        "email", email,
                        "password", password))));
    }

    private String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private Fixture fixture(String prefix, String tenantStatus, String userStatus, String email) {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = prefix + "-" + suffix;
        String authority = "DASHBOARD_VIEW_" + suffix.toUpperCase();
        UUID permissionId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, slug, plan, status, created_at)
                VALUES (?, 'Fictional Clinic', ?, 'STARTER', ?, now())
                """, tenantId, slug, tenantStatus);
        jdbcTemplate.update("""
                INSERT INTO branches (id, tenant_id, name, time_zone, active, created_at)
                VALUES (?, ?, 'Main Branch', 'America/Lima', true, now())
                """, branchId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO users (
                    id, tenant_id, first_name, last_name, email,
                    password_hash, status, created_at
                ) VALUES (?, ?, 'Alex', 'Fictional', ?, ?, ?, now())
                """, userId, tenantId, email, passwordEncoder.encode(PASSWORD), userStatus);
        jdbcTemplate.update("""
                INSERT INTO roles (id, tenant_id, name, system, created_at)
                VALUES (?, ?, 'ADMINISTRADOR', true, now())
                """, roleId, tenantId);
        jdbcTemplate.update("""
                INSERT INTO permissions (id, code, description, module, created_at)
                VALUES (?, ?, 'Fictional access', 'IDENTITY', now())
                """, permissionId, authority);
        jdbcTemplate.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, userId, roleId);
        jdbcTemplate.update("""
                INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
                VALUES (?, ?, ?, now())
                """, tenantId, roleId, permissionId);
        jdbcTemplate.update("""
                INSERT INTO user_branches (tenant_id, user_id, branch_id, primary_branch, assigned_at)
                VALUES (?, ?, ?, true, now())
                """, tenantId, userId, branchId);
        return new Fixture(tenantId, branchId, userId, slug, email, authority);
    }

    private record Fixture(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            String slug,
            String email,
            String authority) {
    }

    private record Attempt(String slug, String email, String password) {
    }
}
