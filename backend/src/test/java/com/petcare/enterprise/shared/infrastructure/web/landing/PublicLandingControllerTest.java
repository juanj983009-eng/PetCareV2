package com.petcare.enterprise.shared.infrastructure.web.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.identity.infrastructure.security.SecurityConfiguration;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseWriter;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = PublicLandingController.class)
@Import({
        SecurityConfiguration.class,
        ApiErrorResponseFactory.class,
        ApiErrorResponseWriter.class
})
class PublicLandingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private CorrelationIdProvider correlationIdProvider;

    @MockitoBean
    private TenantContext tenantContext;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUpCorrelationId() {
        when(correlationIdProvider.currentCorrelationId())
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    }

    @Test
    void rendersPublicPresentationLandingWithThymeleafModelListsAndFragmentsWithoutPostgreSql() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(DataSource.class)).isEmpty();

        MvcResult result = mockMvc.perform(get("/presentacion"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(view().name("presentacion/index"))
                .andExpect(model().attributeExists(
                        "valueProposition",
                        "productPhase",
                        "navigationLinks",
                        "benefits",
                        "implementedModules",
                        "upcomingModules",
                        "technologyStack"))
                .andReturn();

        String html = result.getResponse().getContentAsString();

        // Reusable fragments (header and footer) and primary CTA to "/"
        assertThat(html)
                .contains("<header")
                .contains("<footer")
                .contains("PetCare Enterprise")
                .contains("Ingresar al sistema")
                .contains("href=\"/\"")
                .contains("Estado actual del MVP")
                .contains("id=\"mvpStatusModal\"");

        // Honestly implemented modules rendered via th:each
        assertThat(html)
                .contains("Autenticación multi-tenant")
                .contains("Usuarios, roles y permisos")
                .contains("Propietarios")
                .contains("Pacientes y perfil clínico básico")
                .contains("Agenda, creación, estados, reprogramación y cancelación de citas")
                .contains("MÓDULO 03 · HU-009 · HU-010 · HU-014")
                .contains("MÓDULO 04 · HU-011 · HU-012 · HU-013 · HU-014")
                .doesNotContain("MÓDULO 03 · HU-009 · HU-010 · HU-011")
                .doesNotContain("MÓDULO 04 · HU-012 · HU-013 · HU-014");

        // Upcoming roadmap modules clearly marked as "Próximamente"
        assertThat(html)
                .contains("Próximamente")
                .contains("Consultas clínicas completas")
                .contains("Diagnósticos y prescripciones")
                .contains("Inventario, lotes y alertas")
                .contains("Reportes avanzados");

        // Technology stack rendered
        assertThat(html)
                .contains("Eclipse Temurin Java 17 LTS")
                .contains("Spring Boot 3.5.16")
                .contains("Thymeleaf")
                .contains("PostgreSQL 18")
                .contains("Bootstrap 5.3.3");

        // Must NOT claim unimplemented certifications or features
        assertThat(html)
                .doesNotContainIgnoringCase("HIPAA")
                .doesNotContainIgnoringCase("HL7")
                .doesNotContainIgnoringCase("FHIR")
                .doesNotContainIgnoringCase("WebSocket")
                .doesNotContainIgnoringCase("Kubernetes");
    }

    @Test
    void redirectsTrailingSlashCanonicallyToPresentacionWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/presentacion/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/presentacion"));
    }

    @Test
    void servesDedicatedPresentationStaticAssetsPubliclyWithoutJwt() throws Exception {
        mockMvc.perform(get("/presentacion-assets/css/presentacion.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("--pc-primary: #1D4032")));
    }

    @Test
    void preservesStrictAuthenticationAndUniform401OnProtectedApiEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/owners"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").value("00000000-0000-0000-0000-000000000002"));

        mockMvc.perform(get("/api/v1/appointments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/ruta-no-publica"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
