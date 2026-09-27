CREATE TABLE appointments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    branch_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    veterinarian_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    appointment_type VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason TEXT,
    notes TEXT,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_appointments_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_appointments_branch_tenant
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_appointments_patient_tenant
        FOREIGN KEY (tenant_id, patient_id) REFERENCES patients (tenant_id, id),
    CONSTRAINT fk_appointments_veterinarian_tenant
        FOREIGN KEY (tenant_id, veterinarian_id) REFERENCES users (tenant_id, id),
    CONSTRAINT fk_appointments_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES users (tenant_id, id),
    CONSTRAINT ck_appointments_ends_after_starts CHECK (ends_at > starts_at),
    CONSTRAINT ck_appointments_type_not_blank CHECK (btrim(appointment_type) <> ''),
    CONSTRAINT ck_appointments_status CHECK (
        status IN (
            'PROGRAMADA',
            'CONFIRMADA',
            'EN_ESPERA',
            'EN_ATENCION',
            'FINALIZADA',
            'CANCELADA',
            'NO_ASISTIO'
        )
    )
);

CREATE INDEX idx_appointments_tenant_starts_at
    ON appointments (tenant_id, starts_at, id);

CREATE INDEX idx_appointments_tenant_branch_starts_at
    ON appointments (tenant_id, branch_id, starts_at, id);

CREATE INDEX idx_appointments_tenant_veterinarian_starts_at
    ON appointments (tenant_id, veterinarian_id, starts_at, id);

CREATE INDEX idx_appointments_tenant_patient_starts_at
    ON appointments (tenant_id, patient_id, starts_at DESC, id);

INSERT INTO permissions (id, code, description, module, created_at) VALUES
    ('00000000-0000-4000-8000-000000000711', 'APPOINTMENT_READ', 'Consultar agenda de citas del tenant', 'APPOINTMENTS', now())
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
            'APPOINTMENT_READ'
        ))
        OR (upper(r.name) = 'RECEPCIONISTA' AND p.code IN (
            'DASHBOARD_VIEW',
            'OWNER_READ','OWNER_CREATE','OWNER_UPDATE',
            'PATIENT_CREATE','PATIENT_READ','PATIENT_UPDATE',
            'APPOINTMENT_READ'
        ))
        OR (upper(r.name) = 'VETERINARIO' AND p.code IN (
            'DASHBOARD_VIEW',
            'PATIENT_READ','PATIENT_UPDATE',
            'APPOINTMENT_READ'
        ))
        OR (upper(r.name) = 'ALMACEN' AND p.code = 'DASHBOARD_VIEW')
    WHERE r.tenant_id = target_tenant_id AND r.system = true
    ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
