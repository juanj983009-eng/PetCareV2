package com.petcare.enterprise.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permissions")
class PermissionJpaEntity {

    static final String DASHBOARD_VIEW = "DASHBOARD_VIEW";

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 250)
    private String description;

    @Column(nullable = false, length = 50)
    private String module;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PermissionJpaEntity() {
    }

    private PermissionJpaEntity(Instant createdAt) {
        this.id = UUID.randomUUID();
        this.code = DASHBOARD_VIEW;
        this.description = "Acceder al dashboard inicial";
        this.module = "REPORTING";
        this.createdAt = createdAt;
    }

    static PermissionJpaEntity bootstrapDashboardView(Instant createdAt) {
        return new PermissionJpaEntity(createdAt);
    }

    UUID id() {
        return id;
    }
}
