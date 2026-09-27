CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_patients_tenant_status_species ON patients (tenant_id, status, lower(species));
CREATE INDEX idx_patients_name_search ON patients USING gin (lower(name) gin_trgm_ops);
CREATE INDEX idx_patients_microchip_search ON patients USING gin (lower(coalesce(microchip, '')) gin_trgm_ops);

INSERT INTO permissions (id, code, description, module, created_at)
VALUES ('00000000-0000-4000-8000-000000000708', 'PATIENT_READ', 'Buscar pacientes del tenant', 'CLINICAL', now())
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
        (upper(r.name) = 'ADMINISTRADOR' AND p.code IN ('DASHBOARD_VIEW','USER_READ','USER_MANAGE','ROLE_READ','ROLE_MANAGE','OWNER_READ','OWNER_CREATE','PATIENT_CREATE','PATIENT_READ'))
        OR (upper(r.name) = 'RECEPCIONISTA' AND p.code IN ('DASHBOARD_VIEW','OWNER_READ','OWNER_CREATE','PATIENT_CREATE','PATIENT_READ'))
        OR (upper(r.name) = 'VETERINARIO' AND p.code IN ('DASHBOARD_VIEW','PATIENT_READ'))
        OR (upper(r.name) = 'ALMACEN' AND p.code = 'DASHBOARD_VIEW')
    WHERE r.tenant_id = target_tenant_id AND r.system = true
    ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
