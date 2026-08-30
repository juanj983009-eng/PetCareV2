package com.petcare.enterprise.audit.infrastructure.persistence;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaAuditLogWriter implements AuditLogWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JpaAuditLogWriter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditRecord record) {
        jdbc.update("""
                INSERT INTO audit_entries (
                    id, tenant_id, actor_user_id, branch_id, entity_type, entity_id, action,
                    occurred_at, ip_address, correlation_id, old_data, new_data
                ) VALUES (
                    :id, :tenantId, :actorUserId, :branchId, :entityType, :entityId, :action,
                    :occurredAt, :ipAddress, :correlationId, CAST(:oldData AS jsonb), CAST(:newData AS jsonb)
                )
                """, new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("tenantId", record.tenantId())
                .addValue("actorUserId", record.actorUserId())
                .addValue("branchId", record.branchId())
                .addValue("entityType", record.entityType())
                .addValue("entityId", record.entityId())
                .addValue("action", record.action())
                .addValue("occurredAt", Timestamp.from(record.occurredAt()))
                .addValue("ipAddress", record.ipAddress())
                .addValue("correlationId", record.correlationId())
                .addValue("oldData", json(record.oldData()), Types.VARCHAR)
                .addValue("newData", json(record.newData()), Types.VARCHAR));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit data cannot be serialized", exception);
        }
    }
}
