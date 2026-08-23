package com.petcare.enterprise.identity.infrastructure.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "petcare.bootstrap.enabled=false")
class IdentityBootstrapDisabledIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void disabledBootstrapCreatesNoData() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = 'disabled-bootstrap-fixture'",
                Integer.class)).isZero();
    }
}
