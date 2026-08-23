package com.petcare.enterprise.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BranchTest {

    @Test
    void createsAnActiveMainBranch() {
        UUID tenantId = UUID.randomUUID();
        Branch branch = Branch.createMain(
                UUID.randomUUID(),
                tenantId,
                "Sede Principal",
                "Av. Demo 123",
                "+51 999 000 111",
                "America/Lima",
                Instant.parse("2026-08-23T12:00:00Z"));

        assertThat(branch.tenantId()).isEqualTo(tenantId);
        assertThat(branch.timeZone().getId()).isEqualTo("America/Lima");
        assertThat(branch.active()).isTrue();
    }

    @Test
    void rejectsInvalidTimeZone() {
        assertThatThrownBy(() -> Branch.createMain(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Sede Principal",
                null,
                null,
                "Mars/Olympus",
                Instant.now()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("time zone");
    }
}
