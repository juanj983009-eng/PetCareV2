package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.domain.Branch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "branches")
class BranchJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 250)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(name = "time_zone", nullable = false, length = 50)
    private String timeZone;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BranchJpaEntity() {
    }

    private BranchJpaEntity(Branch branch) {
        this.id = branch.id();
        this.tenantId = branch.tenantId();
        this.name = branch.name();
        this.address = branch.address();
        this.phone = branch.phone();
        this.timeZone = branch.timeZone().getId();
        this.active = branch.active();
        this.createdAt = branch.createdAt();
    }

    static BranchJpaEntity from(Branch branch) {
        return new BranchJpaEntity(branch);
    }

    Branch toDomain() {
        return Branch.restore(id, tenantId, name, address, phone, timeZone, active, createdAt);
    }
}
