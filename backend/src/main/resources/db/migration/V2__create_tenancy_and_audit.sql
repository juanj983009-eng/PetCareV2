CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    ruc VARCHAR(20),
    slug VARCHAR(80) NOT NULL,
    plan VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_tenants_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_tenants_slug_format CHECK (
        char_length(slug) BETWEEN 3 AND 80
        AND slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_tenants_plan CHECK (
        plan IN ('FREE', 'STARTER', 'PROFESSIONAL', 'BUSINESS', 'ENTERPRISE')
    ),
    CONSTRAINT ck_tenants_status CHECK (
        status IN ('ACTIVO', 'SUSPENDIDO', 'CANCELADO')
    ),
    CONSTRAINT uk_tenants_slug UNIQUE (slug)
);

CREATE UNIQUE INDEX uk_tenants_ruc_not_null
    ON tenants (ruc)
    WHERE ruc IS NOT NULL;

CREATE INDEX idx_tenants_status ON tenants (status);

CREATE TABLE branches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(250),
    phone VARCHAR(30),
    time_zone VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_branches_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_branches_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_branches_time_zone_not_blank CHECK (btrim(time_zone) <> ''),
    CONSTRAINT uk_branches_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT uk_branches_tenant_id_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_branches_tenant_active ON branches (tenant_id, active);

CREATE TABLE audit_entries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_user_id UUID,
    branch_id UUID,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    action VARCHAR(50) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    ip_address VARCHAR(50),
    correlation_id UUID NOT NULL,
    old_data JSONB,
    new_data JSONB,
    CONSTRAINT fk_audit_entries_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_audit_entries_branch_tenant
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT ck_audit_entries_entity_type_not_blank CHECK (btrim(entity_type) <> ''),
    CONSTRAINT ck_audit_entries_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_entries_old_data_object CHECK (
        old_data IS NULL OR jsonb_typeof(old_data) = 'object'
    ),
    CONSTRAINT ck_audit_entries_new_data_object CHECK (
        new_data IS NULL OR jsonb_typeof(new_data) = 'object'
    )
);

CREATE INDEX idx_audit_entries_tenant_entity
    ON audit_entries (tenant_id, entity_type, entity_id);

CREATE INDEX idx_audit_entries_tenant_occurred_at
    ON audit_entries (tenant_id, occurred_at DESC);

CREATE INDEX idx_audit_entries_branch_id
    ON audit_entries (branch_id)
    WHERE branch_id IS NOT NULL;

CREATE OR REPLACE FUNCTION reject_audit_entry_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit entries are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_audit_entries_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_entries
FOR EACH STATEMENT
EXECUTE FUNCTION reject_audit_entry_mutation();
