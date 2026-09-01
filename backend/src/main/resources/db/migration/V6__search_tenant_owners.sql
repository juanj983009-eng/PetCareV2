CREATE TABLE owners (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_type VARCHAR(20),
    document_number VARCHAR(30),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_owners_first_name_not_blank CHECK (length(btrim(first_name)) > 0),
    CONSTRAINT ck_owners_last_name_not_blank CHECK (length(btrim(last_name)) > 0),
    CONSTRAINT ck_owners_phone_not_blank CHECK (length(btrim(phone)) > 0)
);

CREATE INDEX idx_owners_tenant ON owners (tenant_id);
CREATE UNIQUE INDEX uq_owners_tenant_document ON owners (tenant_id, document_number) WHERE document_number IS NOT NULL;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_owners_name_search ON owners USING gin (lower(first_name || ' ' || last_name) gin_trgm_ops);
CREATE INDEX idx_owners_document_search ON owners USING gin (lower(coalesce(document_number, '')) gin_trgm_ops);
CREATE INDEX idx_owners_phone_search ON owners USING gin (lower(phone) gin_trgm_ops);

INSERT INTO permissions (id, code, description, module, created_at)
VALUES ('00000000-0000-4000-8000-000000000705', 'OWNER_READ', 'Buscar propietarios del tenant', 'CLINICAL', now())
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
    SELECT r.tenant_id, r.id, p.id, now() FROM roles r JOIN permissions p ON
        (upper(r.name) = 'ADMINISTRADOR' AND p.code IN ('DASHBOARD_VIEW','USER_READ','USER_MANAGE','ROLE_READ','ROLE_MANAGE','OWNER_READ'))
        OR (upper(r.name) = 'RECEPCIONISTA' AND p.code IN ('DASHBOARD_VIEW','OWNER_READ'))
        OR (upper(r.name) IN ('VETERINARIO','ALMACEN') AND p.code = 'DASHBOARD_VIEW')
    WHERE r.tenant_id = target_tenant_id AND r.system = true ON CONFLICT DO NOTHING;
END;
$$;

SELECT ensure_tenant_system_roles(id) FROM tenants;
