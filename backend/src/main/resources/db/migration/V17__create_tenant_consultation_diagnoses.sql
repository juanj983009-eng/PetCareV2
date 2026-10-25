CREATE TABLE diagnoses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    consultation_id UUID NOT NULL,
    code VARCHAR(50),
    description VARCHAR(300) NOT NULL,
    diagnosis_type VARCHAR(20) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_diagnoses_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_diagnoses_consultation_tenant
        FOREIGN KEY (tenant_id, consultation_id) REFERENCES consultations (tenant_id, id),
    CONSTRAINT ck_diagnoses_description_not_blank CHECK (
        btrim(description) <> '' AND length(description) <= 300
    ),
    CONSTRAINT ck_diagnoses_type CHECK (
        diagnosis_type IN ('PRESUNTIVO', 'DEFINITIVO')
    ),
    CONSTRAINT ck_diagnoses_code_length CHECK (
        code IS NULL OR length(btrim(code)) <= 50
    )
);

CREATE INDEX idx_diagnoses_tenant_consultation_created_at
    ON diagnoses (tenant_id, consultation_id, created_at ASC, id);
