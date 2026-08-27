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
    static final String USER_READ = "USER_READ";
    static final String USER_MANAGE = "USER_MANAGE";

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

    private PermissionJpaEntity(String code, String description, String module, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.description = description;
        this.module = module;
        this.createdAt = createdAt;
    }

    static PermissionJpaEntity bootstrapDashboardView(Instant createdAt) {
        return new PermissionJpaEntity(
                DASHBOARD_VIEW, "Acceder al dashboard inicial", "REPORTING", createdAt);
    }

    static PermissionJpaEntity bootstrapUserRead(Instant createdAt) {
        return new PermissionJpaEntity(USER_READ, "Consultar usuarios del tenant", "IDENTITY", createdAt);
    }

    static PermissionJpaEntity bootstrapUserManage(Instant createdAt) {
        return new PermissionJpaEntity(USER_MANAGE, "Gestionar usuarios del tenant", "IDENTITY", createdAt);
    }

    UUID id() {
        return id;
    }
}
