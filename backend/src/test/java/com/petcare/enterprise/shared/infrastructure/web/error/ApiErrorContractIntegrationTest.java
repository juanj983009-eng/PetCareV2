package com.petcare.enterprise.shared.infrastructure.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiErrorContractIntegrationTest.ErrorProbeConfiguration.class)
class ApiErrorContractIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String TENANT_ENDPOINT = "/api/v1/platform/tenants";
    private static final String AUTHORITY = "PLATFORM_TENANT_CREATE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validationErrorsAreSafeNestedAndDeterministicallyOrdered() throws Exception {
        UUID correlationId = UUID.randomUUID();
        Map<String, Object> request = validTenantRequest(uniqueSlug("validation"));
        request.put("plan", null);
        request.put("slug", "Invalid_Slug");
        @SuppressWarnings("unchecked")
        Map<String, Object> branch = (Map<String, Object>) request.get("mainBranch");
        branch.put("name", "");
        branch.put("timeZone", "");

        MvcResult result = mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(platformAuthority())
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Correlation-ID", correlationId.toString()))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Revisa los campos indicados."))
                .andExpect(jsonPath("$.correlationId").value(correlationId.toString()))
                .andExpect(jsonPath("$.fieldErrors.length()").value(4))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("mainBranch.name"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("mainBranch.timeZone"))
                .andExpect(jsonPath("$.fieldErrors[2].field").value("plan"))
                .andExpect(jsonPath("$.fieldErrors[3].field").value("slug"))
                .andExpect(jsonPath("$.fieldErrors[3].code").value("INVALID_FORMAT"))
                .andReturn();

        assertSafeErrorBody(result, correlationId);
    }

    @Test
    void malformedJsonUnknownPropertyAndWrongTypeUseSafeContract() throws Exception {
        assertMalformed("{\"name\":");

        Map<String, Object> unknown = validTenantRequest(uniqueSlug("unknown"));
        unknown.put("tenantId", UUID.randomUUID());
        assertMalformed(json(unknown));

        Map<String, Object> wrongType = validTenantRequest(uniqueSlug("wrong-type"));
        wrongType.put("plan", Map.of("value", "STARTER"));
        assertMalformed(json(wrongType));
    }

    @Test
    void duplicateSlugKeepsSpecificSafeConflictCode() throws Exception {
        String body = json(validTenantRequest(uniqueSlug("duplicate")));

        mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(platformAuthority())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(platformAuthority())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SLUG_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andReturn();

        assertHeaderMatchesBody(result);
    }

    @Test
    void securityErrorsShareTheSameContract() throws Exception {
        String body = json(validTenantRequest(uniqueSlug("security")));

        MvcResult unauthorized = mockMvc.perform(post(TENANT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();
        assertHeaderMatchesBody(unauthorized);

        MvcResult forbidden = mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(user("without-permission"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();
        assertHeaderMatchesBody(forbidden);
    }

    @Test
    void frameworkErrorsUseUniformCodes() throws Exception {
        MvcResult notFound = mockMvc.perform(get("/api/v1/not-present").with(user("test-user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        assertHeaderMatchesBody(notFound);

        MvcResult methodNotAllowed = mockMvc.perform(get(TENANT_ENDPOINT).with(platformAuthority()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andReturn();
        assertHeaderMatchesBody(methodNotAllowed);

        MvcResult unsupported = mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(platformAuthority())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andReturn();
        assertHeaderMatchesBody(unsupported);
    }

    @Test
    void invalidParameterTypeUsesFieldValidationContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test-only/errors/parameter")
                        .with(user("test-user"))
                        .queryParam("number", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("number"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_TYPE"))
                .andReturn();
        assertHeaderMatchesBody(result);

        MvcResult constraint = mockMvc.perform(get("/api/test-only/errors/parameter")
                        .with(user("test-user"))
                        .queryParam("number", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("number"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("INVALID_VALUE"))
                .andReturn();
        assertHeaderMatchesBody(constraint);

        MvcResult required = mockMvc.perform(get("/api/test-only/errors/required")
                        .with(user("test-user"))
                        .queryParam("label", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("label"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"))
                .andReturn();
        assertHeaderMatchesBody(required);
    }

    @Test
    void unexpectedErrorIsGenericAndDoesNotExposeInternals() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/test-only/errors/unexpected")
                        .with(user("test-user")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();

        assertHeaderMatchesBody(result);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(
                "IllegalStateException", "SELECT", "secret-internal-detail", "stackTrace", "tenantId", "userId");
    }

    @Test
    void missingValidInvalidAndExcessiveCorrelationIdsAreHandledSafely() throws Exception {
        MvcResult missing = malformedRequestWithCorrelation(null);
        assertHeaderMatchesBody(missing);

        UUID validId = UUID.randomUUID();
        MvcResult valid = malformedRequestWithCorrelation(validId.toString());
        assertHeaderMatchesBody(valid);
        assertThat(valid.getResponse().getHeader("X-Correlation-ID")).isEqualTo(validId.toString());

        MvcResult invalid = malformedRequestWithCorrelation("not-a-uuid");
        assertHeaderMatchesBody(invalid);
        assertThat(invalid.getResponse().getHeader("X-Correlation-ID")).isNotEqualTo("not-a-uuid");

        String excessive = "x".repeat(500);
        MvcResult oversized = malformedRequestWithCorrelation(excessive);
        assertHeaderMatchesBody(oversized);
        assertThat(oversized.getResponse().getHeader("X-Correlation-ID")).hasSize(36);
    }

    private MvcResult malformedRequestWithCorrelation(String correlationId) throws Exception {
        var request = post(TENANT_ENDPOINT)
                .with(platformAuthority())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":");
        if (correlationId != null) {
            request.header("X-Correlation-ID", correlationId);
        }
        return mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andReturn();
    }

    private void assertMalformed(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(TENANT_ENDPOINT)
                        .with(platformAuthority())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andReturn();
        assertHeaderMatchesBody(result);
    }

    private void assertSafeErrorBody(MvcResult result, UUID correlationId) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("correlationId").asText()).isEqualTo(correlationId.toString());
        assertThat(body.toString()).doesNotContain("Invalid_Slug", "STARTER", "tenantId", "userId", "branchId");
        body.get("fieldErrors").forEach(fieldError -> assertThat(fieldError.has("message")).isTrue());
    }

    private void assertHeaderMatchesBody(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String header = result.getResponse().getHeader("X-Correlation-ID");
        assertThat(header).isNotBlank().isEqualTo(body.get("correlationId").asText());
        assertThat(UUID.fromString(header)).isNotNull();
    }

    private static Map<String, Object> validTenantRequest(String slug) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("name", "Sede Central Ficticia");
        branch.put("address", "Avenida Ficticia 123");
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor platformAuthority() {
        return user("platform-admin").authorities(() -> AUTHORITY);
    }

    private static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorProbeConfiguration {

        @Bean
        ErrorProbeController errorProbeController() {
            return new ErrorProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/test-only/errors")
    static class ErrorProbeController {

        @GetMapping("/parameter")
        Map<String, Integer> parameter(@RequestParam @Min(1) int number) {
            return Map.of("number", number);
        }

        @GetMapping("/required")
        Map<String, String> required(@RequestParam @NotBlank String label) {
            return Map.of("label", label);
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret-internal-detail SELECT * FROM users");
        }
    }
}
