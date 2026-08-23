package com.petcare.enterprise.tenancy.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.tenancy.application.BranchRepository;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TenantControllerIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/platform/tenants";
    private static final String AUTHORITY = "PLATFORM_TENANT_CREATE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BranchRepository branchRepository;

    @Test
    void authorizedPlatformUserCreatesTenantBranchAndAudit() throws Exception {
        String slug = uniqueSlug("authorized");
        UUID correlationId = UUID.randomUUID();

        MvcResult mvcResult = mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(slug))))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.mainBranch.id").isNotEmpty())
                .andExpect(jsonPath("$.mainBranch.active").value(true))
                .andExpect(jsonPath("$.mainBranch.timeZone").value("America/Lima"))
                .andExpect(jsonPath("$.audit").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        UUID tenantId = UUID.fromString(response.get("id").asText());
        UUID branchId = UUID.fromString(response.at("/mainBranch/id").asText());
        assertThat(mvcResult.getResponse().getHeader("Location"))
                .isEqualTo(ENDPOINT + "/" + tenantId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM branches WHERE id = ? AND tenant_id = ?",
                Integer.class,
                branchId,
                tenantId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_entries WHERE tenant_id = ? AND correlation_id = ? AND action = 'TENANT_CREATED'",
                Integer.class,
                tenantId,
                correlationId)).isOne();
    }

    @Test
    void rejectsInvalidDataAndUnknownTenantId() throws Exception {
        Map<String, Object> invalid = request("Invalid_Slug");
        invalid.put("tenantId", UUID.randomUUID().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> branch = (Map<String, Object>) invalid.get("mainBranch");
        branch.put("timeZone", "Mars/Olympus");

        mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void rejectsInvalidTimeZoneWithSafeBadRequest() throws Exception {
        Map<String, Object> invalid = request(uniqueSlug("bad-zone"));
        @SuppressWarnings("unchecked")
        Map<String, Object> branch = (Map<String, Object>) invalid.get("mainBranch");
        branch.put("timeZone", "Mars/Olympus");

        mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsConflictForDuplicateSlug() throws Exception {
        String slug = uniqueSlug("duplicate-api");
        String body = json(request(slug));

        mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SLUG_ALREADY_EXISTS"));
    }

    @Test
    void returnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(uniqueSlug("anonymous")))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void returnsForbiddenWithoutRequiredAuthority() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("ordinary-user").authorities(() -> "SOME_OTHER_PERMISSION"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(uniqueSlug("forbidden")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void tenantAwareBranchPortDoesNotReturnAnotherTenantsBranch() throws Exception {
        JsonNode tenantA = createAuthorized(uniqueSlug("scope-a"));
        JsonNode tenantB = createAuthorized(uniqueSlug("scope-b"));
        UUID tenantAId = UUID.fromString(tenantA.get("id").asText());
        UUID tenantBId = UUID.fromString(tenantB.get("id").asText());
        UUID branchBId = UUID.fromString(tenantB.at("/mainBranch/id").asText());

        assertThat(branchRepository.findByTenantIdAndId(tenantBId, branchBId)).isPresent();
        assertThat(branchRepository.findByTenantIdAndId(tenantAId, branchBId)).isEmpty();
    }

    private JsonNode createAuthorized(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .with(user("platform-admin").authorities(() -> AUTHORITY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(slug))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, Object> request(String slug) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", "Sede Principal " + slug);
        branch.put("address", "Av. Ficticia 123");
        branch.put("phone", "+51 999 000 111");
        branch.put("timeZone", "America/Lima");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "Clínica Veterinaria Ficticia");
        request.put("ruc", null);
        request.put("slug", slug);
        request.put("plan", "STARTER");
        request.put("status", "ACTIVO");
        request.put("mainBranch", branch);
        return request;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
