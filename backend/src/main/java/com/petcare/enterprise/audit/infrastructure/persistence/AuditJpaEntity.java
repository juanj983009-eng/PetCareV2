package com.petcare.enterprise.audit.infrastructure.persistence;

import com.petcare.enterprise.audit.application.AuditRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_entries")
class AuditJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_data", columnDefinition = "jsonb")
    private Map<String, Object> oldData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_data", columnDefinition = "jsonb")
    private Map<String, Object> newData;

    protected AuditJpaEntity() {
    }

    private AuditJpaEntity(AuditRecord record) {
        this.id = record.id();
        this.tenantId = record.tenantId();
        this.actorUserId = record.actorUserId();
        this.branchId = record.branchId();
        this.entityType = record.entityType();
        this.entityId = record.entityId();
        this.action = record.action();
        this.occurredAt = record.occurredAt();
        this.ipAddress = record.ipAddress();
        this.correlationId = record.correlationId();
        this.oldData = record.oldData();
        this.newData = record.newData();
    }

    static AuditJpaEntity from(AuditRecord record) {
        return new AuditJpaEntity(record);
    }
}
