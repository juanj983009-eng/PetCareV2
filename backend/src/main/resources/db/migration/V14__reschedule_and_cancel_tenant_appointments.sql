ALTER TABLE appointments
    ADD COLUMN cancellation_reason TEXT,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID;

UPDATE appointments
SET cancellation_reason = COALESCE(NULLIF(btrim(cancellation_reason), ''), '[LEGADO V14] Cancelación previa sin motivo registrado'),
    cancelled_at = COALESCE(cancelled_at, updated_at, created_at, now()),
    cancelled_by = COALESCE(cancelled_by, created_by)
WHERE status = 'CANCELADA';

UPDATE appointments
SET cancellation_reason = NULL,
    cancelled_at = NULL,
    cancelled_by = NULL
WHERE status <> 'CANCELADA';

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_cancelled_by_tenant
        FOREIGN KEY (tenant_id, cancelled_by) REFERENCES users (tenant_id, id),
    ADD CONSTRAINT ck_appointments_cancellation_metadata CHECK (
        (
            status = 'CANCELADA'
            AND cancellation_reason IS NOT NULL
            AND char_length(btrim(cancellation_reason)) BETWEEN 3 AND 500
            AND cancelled_at IS NOT NULL
            AND cancelled_by IS NOT NULL
        )
        OR (
            status <> 'CANCELADA'
            AND cancellation_reason IS NULL
            AND cancelled_at IS NULL
            AND cancelled_by IS NULL
        )
    );

INSERT INTO permissions (id, code, description, module, created_at) VALUES
    ('00000000-0000-4000-8000-000000000714', 'APPOINTMENT_RESCHEDULE', 'Reprogramar citas en la agenda del tenant', 'APPOINTMENTS', now()),
    ('00000000-0000-4000-8000-000000000715', 'APPOINTMENT_CANCEL', 'Cancelar citas en la agenda del tenant', 'APPOINTMENTS', now())
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
            'APPOINTMENT_READ'
        ))
        OR (upper(r.name) = 'ALMACEN' AND p.code = 'DASHBOARD_VIEW')
    WHERE r.tenant_id = target_tenant_id AND r.system = true
    ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
