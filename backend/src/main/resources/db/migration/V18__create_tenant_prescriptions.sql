CREATE TABLE prescriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    consultation_id UUID NOT NULL,
    prescribed_by UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    general_instructions TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'EMITIDA',
    idempotency_key VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_prescriptions_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_prescriptions_consultation_tenant
        FOREIGN KEY (tenant_id, consultation_id) REFERENCES consultations (tenant_id, id),
    CONSTRAINT fk_prescriptions_prescribed_by_tenant
        FOREIGN KEY (tenant_id, prescribed_by) REFERENCES users (tenant_id, id),
    CONSTRAINT ck_prescriptions_status CHECK (
        status IN ('BORRADOR', 'EMITIDA', 'ANULADA')
    ),
    CONSTRAINT ck_prescriptions_general_instructions_length CHECK (
        general_instructions IS NULL OR length(general_instructions) <= 2000
    )
);

CREATE INDEX idx_prescriptions_tenant_consultation_created_at
    ON prescriptions (tenant_id, consultation_id, created_at ASC, id);

CREATE UNIQUE INDEX uk_prescriptions_tenant_consultation_idempotency
    ON prescriptions (tenant_id, consultation_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE prescription_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    prescription_id UUID NOT NULL,
    item_order INT NOT NULL,
    medication_name VARCHAR(200) NOT NULL,
    dosage VARCHAR(100) NOT NULL,
    frequency VARCHAR(100) NOT NULL,
    duration VARCHAR(100) NOT NULL,
    route VARCHAR(50),
    instructions TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_prescription_items_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_prescription_items_prescription_tenant
        FOREIGN KEY (tenant_id, prescription_id) REFERENCES prescriptions (tenant_id, id),
    CONSTRAINT ck_prescription_items_medication_not_blank CHECK (
        btrim(medication_name) <> '' AND length(medication_name) <= 200
    ),
    CONSTRAINT ck_prescription_items_dosage_not_blank CHECK (
        btrim(dosage) <> '' AND length(dosage) <= 100
    ),
    CONSTRAINT ck_prescription_items_frequency_not_blank CHECK (
        btrim(frequency) <> '' AND length(frequency) <= 100
    ),
    CONSTRAINT ck_prescription_items_duration_not_blank CHECK (
        btrim(duration) <> '' AND length(duration) <= 100
    ),
    CONSTRAINT ck_prescription_items_route_length CHECK (
        route IS NULL OR length(btrim(route)) <= 50
    ),
    CONSTRAINT ck_prescription_items_instructions_length CHECK (
        instructions IS NULL OR length(instructions) <= 2000
    )
);

CREATE INDEX idx_prescription_items_tenant_prescription_order
    ON prescription_items (tenant_id, prescription_id, item_order ASC, id);
