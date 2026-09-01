package com.petcare.enterprise.clinical.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        Fixture tenantA = fixture("owners-a"); Fixture tenantB = fixture("owners-b");
        owner(tenantA.tenantId(), "María", "López", "DNI", "74210001", "999111222");
        owner(tenantA.tenantId(), "Mario", "Torres", "DNI", "74210002", "999111333");
        owner(tenantB.tenantId(), "María", "Ajena", "DNI", "74210001", "988444555");
        mockMvc.perform(get("/api/v1/owners?query=mar&page=0&size=1&sortBy=name&direction=asc")
                        .with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/owners?query=2100").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/api/v1/owners?query=1113").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].lastName").value("Torres"));
        mockMvc.perform(get("/api/v1/owners?query=ajena").with(authentication(auth(tenantA, "OWNER_READ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void requiresPermissionAndRejectsUnsafeQuery() throws Exception {
        Fixture fixture = fixture("owners-security");
        mockMvc.perform(get("/api/v1/owners?query=ma")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/owners?query=ma").with(authentication(auth(fixture, "USER_READ")))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/owners?query=x").with(authentication(auth(fixture, "OWNER_READ")))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/owners").param("query", "  ").with(authentication(auth(fixture, "OWNER_READ")))).andExpect(status().isBadRequest());
    }

    private Fixture fixture(String prefix) {
        UUID tenant = UUID.randomUUID(), branch = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id,name,slug,plan,status,created_at) VALUES (?,?,?,'STARTER','ACTIVO',now())", tenant, "Fictional clinic", prefix + tenant.toString().substring(0, 8));
        jdbc.update("INSERT INTO branches (id,tenant_id,name,time_zone,active,created_at) VALUES (?,?,'Main','America/Lima',true,now())", branch, tenant);
        jdbc.queryForObject("SELECT ensure_tenant_system_roles(?)", Object.class, tenant);
        return new Fixture(tenant, branch, user);
    }
    private void owner(UUID tenant, String first, String last, String type, String document, String phone) {
        jdbc.update("INSERT INTO owners (id,tenant_id,first_name,last_name,document_type,document_number,phone) VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, first, last, type, document, phone);
    }
    private UsernamePasswordAuthenticationToken auth(Fixture fixture, String... permissions) {
        var authorities = List.of(permissions).stream().map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(new PetCarePrincipal(fixture.userId(), fixture.tenantId(), fixture.branchId(), authorities), null, authorities);
    }
    private record Fixture(UUID tenantId, UUID branchId, UUID userId) { }
}
