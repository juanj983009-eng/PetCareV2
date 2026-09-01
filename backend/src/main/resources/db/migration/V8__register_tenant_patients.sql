ALTER TABLE owners ADD CONSTRAINT uk_owners_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE patients (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    owner_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    species VARCHAR(50) NOT NULL,
    breed VARCHAR(100),
    sex VARCHAR(20) NOT NULL,
    birth_date DATE,
    color VARCHAR(80),
    microchip VARCHAR(80),
    sterilized BOOLEAN,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVO',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_patients_owner_tenant FOREIGN KEY (tenant_id, owner_id) REFERENCES owners(tenant_id, id),
    CONSTRAINT uk_patients_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_patients_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_patients_species_not_blank CHECK (btrim(species) <> ''),
    CONSTRAINT ck_patients_sex_not_blank CHECK (btrim(sex) <> ''),
    CONSTRAINT ck_patients_birth_date_not_future CHECK (birth_date IS NULL OR birth_date <= CURRENT_DATE),
    CONSTRAINT ck_patients_status CHECK (status IN ('ACTIVO','FALLECIDO','INACTIVO'))
);
CREATE INDEX idx_patients_tenant_owner ON patients(tenant_id, owner_id);
CREATE UNIQUE INDEX uq_patients_tenant_microchip ON patients(tenant_id, microchip) WHERE microchip IS NOT NULL;

CREATE TABLE medical_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    patient_id UUID NOT NULL,
    allergies TEXT,
    history TEXT,
    medical_alerts TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_medical_records_patient_tenant FOREIGN KEY (tenant_id, patient_id) REFERENCES patients(tenant_id, id),
    CONSTRAINT uk_medical_records_patient UNIQUE (patient_id),
    CONSTRAINT uk_medical_records_tenant_patient UNIQUE (tenant_id, patient_id)
);

INSERT INTO permissions (id, code, description, module, created_at) VALUES
('00000000-0000-4000-8000-000000000707','PATIENT_CREATE','Registrar pacientes del tenant','CLINICAL',now()) ON CONFLICT (code) DO NOTHING;

CREATE OR REPLACE FUNCTION ensure_tenant_system_roles(target_tenant_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO roles (id,tenant_id,name,description,system,created_at) VALUES
 (gen_random_uuid(),target_tenant_id,'ADMINISTRADOR','Administración del tenant',true,now()),(gen_random_uuid(),target_tenant_id,'VETERINARIO','Atención clínica veterinaria',true,now()),(gen_random_uuid(),target_tenant_id,'RECEPCIONISTA','Atención y recepción',true,now()),(gen_random_uuid(),target_tenant_id,'ALMACEN','Gestión operativa de almacén',true,now()) ON CONFLICT DO NOTHING;
 INSERT INTO role_permissions (tenant_id,role_id,permission_id,assigned_at)
 SELECT r.tenant_id,r.id,p.id,now() FROM roles r JOIN permissions p ON
 (upper(r.name)='ADMINISTRADOR' AND p.code IN ('DASHBOARD_VIEW','USER_READ','USER_MANAGE','ROLE_READ','ROLE_MANAGE','OWNER_READ','OWNER_CREATE','PATIENT_CREATE')) OR
 (upper(r.name)='RECEPCIONISTA' AND p.code IN ('DASHBOARD_VIEW','OWNER_READ','OWNER_CREATE','PATIENT_CREATE')) OR
 (upper(r.name) IN ('VETERINARIO','ALMACEN') AND p.code='DASHBOARD_VIEW')
 WHERE r.tenant_id=target_tenant_id AND r.system=true ON CONFLICT DO NOTHING;
END; $$;
SELECT ensure_tenant_system_roles(id) FROM tenants;
