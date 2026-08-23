package com.petcare.enterprise.audit.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(
        UUID id,
        UUID tenantId,
        UUID actorUserId,
        UUID branchId,
        String entityType,
        UUID entityId,
        String action,
        Instant occurredAt,
        String ipAddress,
        UUID correlationId,
        Map<String, Object> oldData,
        Map<String, Object> newData) {
}
