package com.petcare.enterprise;

import com.petcare.enterprise.test.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PetCareEnterpriseApplicationTest extends PostgreSqlIntegrationTest {

    @Test
    void contextLoadsAgainstPostgreSql() {
    }
}
