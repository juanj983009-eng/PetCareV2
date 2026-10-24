ALTER TABLE medical_records
    ADD CONSTRAINT uk_medical_records_tenant_id_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT uk_medical_records_tenant_patient_id UNIQUE (tenant_id, patient_id, id);

ALTER TABLE appointments
    ADD CONSTRAINT uk_appointments_tenant_consultation_context
        UNIQUE (tenant_id, id, patient_id, branch_id, veterinarian_id);

CREATE TABLE consultations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    appointment_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    medical_record_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    veterinarian_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT,
    anamnesis TEXT,
    physical_exam TEXT,
    clinical_details JSONB,
    plan TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_consultations_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_consultations_appointment_id UNIQUE (appointment_id),
    CONSTRAINT uk_consultations_tenant_appointment_id UNIQUE (tenant_id, appointment_id),
    CONSTRAINT fk_consultations_appointment_tenant
        FOREIGN KEY (tenant_id, appointment_id) REFERENCES appointments (tenant_id, id),
    CONSTRAINT fk_consultations_appointment_context
        FOREIGN KEY (tenant_id, appointment_id, patient_id, branch_id, veterinarian_id)
        REFERENCES appointments (tenant_id, id, patient_id, branch_id, veterinarian_id),
    CONSTRAINT fk_consultations_patient_tenant
        FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
    CONSTRAINT fk_consultations_medical_record_patient_tenant
        FOREIGN KEY (tenant_id, patient_id, medical_record_id)
        REFERENCES medical_records (tenant_id, patient_id, id),
    CONSTRAINT fk_consultations_branch_tenant
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_consultations_veterinarian_tenant
        FOREIGN KEY (tenant_id, veterinarian_id) REFERENCES users (tenant_id, id),
    CONSTRAINT fk_consultations_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id),
    CONSTRAINT ck_consultations_status CHECK (
        status IN ('BORRADOR', 'FINALIZADA', 'ANULADA')
    )
);

CREATE INDEX idx_consultations_tenant_patient_started_at
    ON consultations (tenant_id, patient_id, started_at DESC, id);

CREATE INDEX idx_consultations_tenant_medical_record_started_at
    ON consultations (tenant_id, medical_record_id, started_at DESC, id);

CREATE INDEX idx_consultations_tenant_veterinarian_started_at
    ON consultations (tenant_id, veterinarian_id, started_at DESC, id);

INSERT INTO permissions (id, code, description, module, created_at) VALUES
    ('00000000-0000-4000-8000-000000000716', 'CONSULTATION_CREATE', 'Iniciar consultas clínicas desde citas del tenant', 'CLINICAL', now())
ON CONFLICT (code) DO NOTHING;

CREATE OR REPLACE FUNCTION ensure_tenant_system_roles(target_tenant_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO roles (id, tenant_id, name, description, system, created_at) VALUES
        (gen_random_uuid(), target_tenant_id, 'ADMINISTRADOR', 'Administración del tenant', true, now()),
        (gen_random_uuid(), target_tenant_id, 'VETERINARIO', 'Atención clínica veterinaria', true, now()),
        (gen_random_uuid(), target_tenant_id, 'RECEPCIONISTA', 'Atención y recepción', true, now()),
        (gen_random_uuid(), target_tenant_id, 'ALMACEN', 'Gestión operativa de almacén', true, now())
    ON CONFLICT DO NOTHING;

    INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
    SELECT r.tenant_id, r.id, p.id, now()
    FROM roles r
    JOIN permissions p ON
        (upper(r.name) = 'ADMINISTRADOR' AND p.code IN (
            'DASHBOARD_VIEW','USER_READ','USER_MANAGE','ROLE_READ','ROLE_MANAGE',
            'OWNER_READ','OWNER_CREATE','OWNER_UPDATE',
            'PATIENT_CREATE','PATIENT_READ','PATIENT_UPDATE',
            'APPOINTMENT_READ','APPOINTMENT_CREATE','APPOINTMENT_STATUS_UPDATE',
            'APPOINTMENT_RESCHEDULE','APPOINTMENT_CANCEL'
        ))
        OR (upper(r.name) = 'RECEPCIONISTA' AND p.code IN (
            'DASHBOARD_VIEW',
            'OWNER_READ','OWNER_CREATE','OWNER_UPDATE',
            'PATIENT_CREATE','PATIENT_READ','PATIENT_UPDATE',
            'APPOINTMENT_READ','APPOINTMENT_CREATE','APPOINTMENT_STATUS_UPDATE',
            'APPOINTMENT_RESCHEDULE','APPOINTMENT_CANCEL'
        ))
        OR (upper(r.name) = 'VETERINARIO' AND p.code IN (
            'DASHBOARD_VIEW',
            'PATIENT_READ','PATIENT_UPDATE',
            'APPOINTMENT_READ','CONSULTATION_CREATE'
        ))
        OR (upper(r.name) = 'ALMACEN' AND p.code = 'DASHBOARD_VIEW')
    WHERE r.tenant_id = target_tenant_id AND r.system = true
    ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
