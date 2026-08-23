package com.petcare.enterprise.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    void createsAValidTenant() {
        Tenant tenant = Tenant.create(
                UUID.randomUUID(),
                "PetCare Demo",
                "20123456789",
                "petcare-demo",
                TenantPlan.STARTER,
                TenantStatus.ACTIVO,
                CREATED_AT);

        assertThat(tenant.name()).isEqualTo("PetCare Demo");
        assertThat(tenant.slug()).isEqualTo("petcare-demo");
        assertThat(tenant.plan()).isEqualTo(TenantPlan.STARTER);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVO);
        assertThat(tenant.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsBlankNameAndInvalidSlug() {
        assertThatThrownBy(() -> tenant(" ", "valid-slug"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> tenant("Valid name", "Invalid_Slug"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> tenant("Valid name", "-invalid"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void requiresControlledPlanAndStatus() {
        assertThatThrownBy(() -> Tenant.create(
                UUID.randomUUID(),
                "PetCare Demo",
                null,
                "petcare-demo",
                null,
                TenantStatus.ACTIVO,
                CREATED_AT)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Tenant.create(
                UUID.randomUUID(),
                "PetCare Demo",
                null,
                "petcare-demo",
                TenantPlan.FREE,
                null,
                CREATED_AT)).isInstanceOf(NullPointerException.class);
    }

    private static Tenant tenant(String name, String slug) {
        return Tenant.create(
                UUID.randomUUID(),
                name,
                null,
                slug,
                TenantPlan.FREE,
                TenantStatus.ACTIVO,
                CREATED_AT);
    }
}
