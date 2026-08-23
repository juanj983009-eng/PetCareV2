package com.petcare.enterprise.audit.application;

public interface AuditLogWriter {

    void append(AuditRecord record);
}
