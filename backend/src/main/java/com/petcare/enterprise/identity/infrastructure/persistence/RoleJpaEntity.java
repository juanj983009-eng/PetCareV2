package com.petcare.enterprise.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roles")
class RoleJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 250)
    private String description;

    @Column(nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoleJpaEntity() {
    }

    private RoleJpaEntity(UUID tenantId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = "ADMINISTRADOR";
        this.description = "Administrador inicial del tenant";
        this.system = true;
        this.createdAt = createdAt;
    }

    static RoleJpaEntity bootstrapAdministrator(UUID tenantId, Instant createdAt) {
        return new RoleJpaEntity(tenantId, createdAt);
    }

    UUID id() {
        return id;
    }
}
