ALTER TABLE users
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE users
SET updated_at = created_at;

ALTER TABLE users
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_users_version_non_negative CHECK (version >= 0);

CREATE INDEX idx_users_tenant_name_email
    ON users (tenant_id, lower(first_name), lower(last_name), email);

INSERT INTO permissions (id, code, description, module, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000701', 'USER_READ', 'Consultar usuarios del tenant', 'IDENTITY', now()),
    ('00000000-0000-4000-8000-000000000702', 'USER_MANAGE', 'Gestionar usuarios del tenant', 'IDENTITY', now())
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id, assigned_at)
SELECT r.tenant_id, r.id, p.id, now()
FROM roles r
JOIN permissions p ON p.code IN ('USER_READ', 'USER_MANAGE')
WHERE upper(r.name) = 'ADMINISTRADOR'
  AND r.system = true
ON CONFLICT DO NOTHING;
