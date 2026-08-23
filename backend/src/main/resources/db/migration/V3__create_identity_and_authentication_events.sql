CREATE TABLE users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(180) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_access_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_users_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_users_first_name_not_blank CHECK (btrim(first_name) <> ''),
    CONSTRAINT ck_users_last_name_not_blank CHECK (btrim(last_name) <> ''),
    CONSTRAINT ck_users_email_normalized CHECK (
        email = lower(btrim(email)) AND email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
    ),
    CONSTRAINT ck_users_password_hash_not_blank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVO', 'BLOQUEADO', 'INACTIVO')),
    CONSTRAINT uk_users_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT uk_users_tenant_id_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_users_tenant_status ON users (tenant_id, status);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(250),
    system BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_roles_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_roles_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT uk_roles_tenant_id_id UNIQUE (tenant_id, id)
);

CREATE UNIQUE INDEX uk_roles_tenant_name
    ON roles (tenant_id, lower(name));

CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    description VARCHAR(250) NOT NULL,
    module VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_permissions_code_format CHECK (code ~ '^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$'),
    CONSTRAINT ck_permissions_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT ck_permissions_module_not_blank CHECK (btrim(module) <> ''),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE user_roles (
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT fk_user_roles_user_tenant
        FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CONSTRAINT fk_user_roles_role_tenant
        FOREIGN KEY (tenant_id, role_id) REFERENCES roles (tenant_id, id)
);

CREATE INDEX idx_user_roles_tenant_user ON user_roles (tenant_id, user_id);

CREATE TABLE role_permissions (
    tenant_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (tenant_id, role_id, permission_id),
    CONSTRAINT fk_role_permissions_role_tenant
        FOREIGN KEY (tenant_id, role_id) REFERENCES roles (tenant_id, id),
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

CREATE INDEX idx_role_permissions_tenant_role ON role_permissions (tenant_id, role_id);

CREATE TABLE user_branches (
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    primary_branch BOOLEAN NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_user_branches PRIMARY KEY (tenant_id, user_id, branch_id),
    CONSTRAINT fk_user_branches_user_tenant
        FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CONSTRAINT fk_user_branches_branch_tenant
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);

CREATE UNIQUE INDEX uk_user_branches_primary
    ON user_branches (tenant_id, user_id)
    WHERE primary_branch;

CREATE INDEX idx_user_branches_tenant_branch ON user_branches (tenant_id, branch_id);

CREATE TABLE authentication_events (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    user_id UUID,
    event_type VARCHAR(50) NOT NULL,
    result VARCHAR(30) NOT NULL,
    internal_reason VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    ip_address VARCHAR(50),
    CONSTRAINT fk_authentication_events_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_authentication_events_user_tenant
        FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CONSTRAINT ck_authentication_events_user_requires_tenant CHECK (
        user_id IS NULL OR tenant_id IS NOT NULL
    ),
    CONSTRAINT ck_authentication_events_type CHECK (event_type IN ('LOGIN')),
    CONSTRAINT ck_authentication_events_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_authentication_events_reason CHECK (internal_reason IN (
        'AUTHENTICATED',
        'TENANT_NOT_FOUND',
        'TENANT_INACTIVE',
        'USER_NOT_FOUND',
        'USER_INACTIVE',
        'USER_BLOCKED',
        'BAD_PASSWORD',
        'BRANCH_INVALID'
    ))
);

CREATE INDEX idx_authentication_events_tenant_occurred_at
    ON authentication_events (tenant_id, occurred_at DESC)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX idx_authentication_events_user_occurred_at
    ON authentication_events (user_id, occurred_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_authentication_events_correlation_id
    ON authentication_events (correlation_id);

CREATE OR REPLACE FUNCTION reject_authentication_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Authentication events are append-only'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_authentication_events_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON authentication_events
FOR EACH STATEMENT
EXECUTE FUNCTION reject_authentication_event_mutation();
