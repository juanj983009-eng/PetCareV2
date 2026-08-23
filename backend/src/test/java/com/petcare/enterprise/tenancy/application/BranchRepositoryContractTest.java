package com.petcare.enterprise.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BranchRepositoryContractTest {

    @Test
    void businessLookupsCannotOmitTenantScope() throws Exception {
        Method lookup = BranchRepository.class.getMethod(
                "findByTenantIdAndId", UUID.class, UUID.class);

        assertThat(lookup.getParameterTypes()).containsExactly(UUID.class, UUID.class);
        assertThat(Arrays.stream(BranchRepository.class.getMethods())
                .map(Method::getName))
                .doesNotContain("findById", "findAll", "deleteById");
    }
}
