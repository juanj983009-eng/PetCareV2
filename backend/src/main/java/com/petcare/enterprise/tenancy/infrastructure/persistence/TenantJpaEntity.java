package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.domain.Tenant;
import com.petcare.enterprise.tenancy.domain.TenantPlan;
import com.petcare.enterprise.tenancy.domain.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
class TenantJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 20)
    private String ruc;

    @Column(nullable = false, length = 80)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TenantPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantJpaEntity() {
    }

    private TenantJpaEntity(Tenant tenant) {
        this.id = tenant.id();
        this.name = tenant.name();
        this.ruc = tenant.ruc();
        this.slug = tenant.slug();
        this.plan = tenant.plan();
        this.status = tenant.status();
        this.createdAt = tenant.createdAt();
    }

    static TenantJpaEntity from(Tenant tenant) {
        return new TenantJpaEntity(tenant);
    }
}
