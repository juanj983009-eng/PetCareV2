package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.AuthenticationEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "authentication_events")
class AuthenticationEventJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuthenticationEvent.Type eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuthenticationEvent.Result result;

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_reason", nullable = false, length = 80)
    private AuthenticationEvent.Reason internalReason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    protected AuthenticationEventJpaEntity() {
    }

    private AuthenticationEventJpaEntity(AuthenticationEvent event) {
        this.id = event.id();
        this.tenantId = event.tenantId();
        this.userId = event.userId();
        this.eventType = event.type();
        this.result = event.result();
        this.internalReason = event.reason();
        this.occurredAt = event.occurredAt();
        this.correlationId = event.correlationId();
    }

    static AuthenticationEventJpaEntity from(AuthenticationEvent event) {
        return new AuthenticationEventJpaEntity(event);
    }
}
