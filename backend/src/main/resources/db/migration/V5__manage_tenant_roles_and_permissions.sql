INSERT INTO permissions (id, code, description, module, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000703', 'ROLE_READ', 'Consultar roles y permisos del tenant', 'IDENTITY', now()),
    ('00000000-0000-4000-8000-000000000704', 'ROLE_MANAGE', 'Gestionar asignaciones y permisos de roles del tenant', 'IDENTITY', now())
ON CONFLICT (code) DO NOTHING;

CREATE OR REPLACE FUNCTION ensure_tenant_system_roles(target_tenant_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO roles (id, tenant_id, name, description, system, created_at)
    VALUES
        (gen_random_uuid(), target_tenant_id, 'ADMINISTRADOR', 'Administración del tenant', true, now()),
        (gen_random_uuid(), target_tenant_id, 'VETERINARIO', 'Atención clínica veterinaria', true, now()),
        (gen_random_uuid(), target_tenant_id, 'RECEPCIONISTA', 'Atención y recepción', true, now()),
        (gen_random_uuid(), target_tenant_id, 'ALMACEN', 'Gestión operativa de almacén', true, now())
    ON CONFLICT DO NOTHING;

    INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
    SELECT r.tenant_id, r.id, p.id, now()
    FROM roles r
    JOIN permissions p ON (
        (upper(r.name) = 'ADMINISTRADOR' AND p.code IN (
            'DASHBOARD_VIEW', 'USER_READ', 'USER_MANAGE', 'ROLE_READ', 'ROLE_MANAGE'))
        OR (upper(r.name) IN ('VETERINARIO', 'RECEPCIONISTA', 'ALMACEN')
            AND p.code = 'DASHBOARD_VIEW')
    )
    WHERE r.tenant_id = target_tenant_id
      AND r.system = true
    ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
