package com.petcare.enterprise.audit.infrastructure.persistence;

import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditRecord;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
class JpaAuditLogWriter implements AuditLogWriter {

    private final EntityManager entityManager;

    JpaAuditLogWriter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void append(AuditRecord record) {
        entityManager.persist(AuditJpaEntity.from(record));
        entityManager.flush();
    }
}
