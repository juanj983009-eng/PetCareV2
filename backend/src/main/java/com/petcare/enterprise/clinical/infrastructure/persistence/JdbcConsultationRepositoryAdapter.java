package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.clinical.application.ConsultationRepository;
import com.petcare.enterprise.clinical.application.ConsultationResourceNotFoundException;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.ConsultationSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import com.petcare.enterprise.clinical.application.InvalidConsultationStatusException;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConsultationRepositoryAdapter implements ConsultationRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcConsultationRepositoryAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public Optional<ActivePatientMedicalRecord> findActivePatientMedicalRecord(UUID tenantId, UUID patientId) {
        String sql = """
                SELECT
                    p.id AS patient_id,
                    p.name AS patient_name,
                    mr.id AS medical_record_id
                FROM patients p
                JOIN medical_records mr
                    ON mr.tenant_id = p.tenant_id
                   AND mr.patient_id = p.id
                WHERE p.tenant_id = :tenant_id
                  AND p.id = :patient_id
                  AND p.status = 'ACTIVO'
                """;
        List<ActivePatientMedicalRecord> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("patient_id", patientId),
                (rs, rowNum) -> new ActivePatientMedicalRecord(
                        rs.getObject("patient_id", UUID.class),
                        rs.getString("patient_name"),
                        rs.getObject("medical_record_id", UUID.class)));
        return rows.stream().findFirst();
    }

    @Override
    public boolean patientExists(UUID tenantId, UUID patientId) {
        String sql = """
                SELECT 1 FROM patients
                WHERE tenant_id = :tenant_id AND id = :patient_id
                """;
        List<Integer> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("patient_id", patientId),
                (rs, rowNum) -> 1);
        return !rows.isEmpty();
    }

    @Override
    public Optional<ConsultationSummary> findById(UUID tenantId, UUID consultationId) {
        String sql = """
                SELECT
                    c.id,
                    c.appointment_id,
                    c.patient_id,
                    p.name AS patient_name,
                    c.medical_record_id,
                    c.branch_id,
                    b.name AS branch_name,
                    c.veterinarian_id,
                    btrim(v.first_name || ' ' || v.last_name) AS veterinarian_full_name,
                    c.status,
                    c.started_at,
                    c.reason,
                    c.anamnesis,
                    c.physical_exam,
                    c.clinical_details,
                    c.created_at,
                    c.updated_at
                FROM consultations c
                JOIN patients p
                    ON p.tenant_id = c.tenant_id
                   AND p.id = c.patient_id
                JOIN branches b
                    ON b.tenant_id = c.tenant_id
                   AND b.id = c.branch_id
                JOIN users v
                    ON v.tenant_id = c.tenant_id
                   AND v.id = c.veterinarian_id
                WHERE c.tenant_id = :tenant_id
                  AND c.id = :consultation_id
                """;
        List<ConsultationSummary> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId),
                this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ConsultationSummary> lockConsultationForUpdate(UUID tenantId, UUID consultationId) {
        String sql = """
                SELECT
                    c.id,
                    c.appointment_id,
                    c.patient_id,
                    p.name AS patient_name,
                    c.medical_record_id,
                    c.branch_id,
                    b.name AS branch_name,
                    c.veterinarian_id,
                    btrim(v.first_name || ' ' || v.last_name) AS veterinarian_full_name,
                    c.status,
                    c.started_at,
                    c.reason,
                    c.anamnesis,
                    c.physical_exam,
                    c.clinical_details,
                    c.created_at,
                    c.updated_at
                FROM consultations c
                JOIN patients p
                    ON p.tenant_id = c.tenant_id
                   AND p.id = c.patient_id
                JOIN branches b
                    ON b.tenant_id = c.tenant_id
                   AND b.id = c.branch_id
                JOIN users v
                    ON v.tenant_id = c.tenant_id
                   AND v.id = c.veterinarian_id
                WHERE c.tenant_id = :tenant_id
                  AND c.id = :consultation_id
                FOR UPDATE OF c
                """;
        List<ConsultationSummary> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId),
                this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ConsultationSummary> findByAppointmentId(UUID tenantId, UUID appointmentId) {
        String sql = """
                SELECT
                    c.id,
                    c.appointment_id,
                    c.patient_id,
                    p.name AS patient_name,
                    c.medical_record_id,
                    c.branch_id,
                    b.name AS branch_name,
                    c.veterinarian_id,
                    btrim(v.first_name || ' ' || v.last_name) AS veterinarian_full_name,
                    c.status,
                    c.started_at,
                    c.reason,
                    c.anamnesis,
                    c.physical_exam,
                    c.clinical_details,
                    c.created_at,
                    c.updated_at
                FROM consultations c
                JOIN patients p
                    ON p.tenant_id = c.tenant_id
                   AND p.id = c.patient_id
                JOIN branches b
                    ON b.tenant_id = c.tenant_id
                   AND b.id = c.branch_id
                JOIN users v
                    ON v.tenant_id = c.tenant_id
                   AND v.id = c.veterinarian_id
                WHERE c.tenant_id = :tenant_id
                  AND c.appointment_id = :appointment_id
                """;
        List<ConsultationSummary> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("appointment_id", appointmentId),
                this::mapRow);
        return rows.stream().findFirst();
    }

    @Override
    public ConsultationSummary insert(NewConsultationRecord record) {
        String sql = """
                INSERT INTO consultations (
                    id,
                    tenant_id,
                    appointment_id,
                    patient_id,
                    medical_record_id,
                    branch_id,
                    veterinarian_id,
                    started_at,
                    status,
                    created_by,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :tenant_id,
                    :appointment_id,
                    :patient_id,
                    :medical_record_id,
                    :branch_id,
                    :veterinarian_id,
                    :started_at,
                    :status,
                    :created_by,
                    :created_at,
                    :updated_at
                )
                """;
        jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", record.id())
                        .addValue("tenant_id", record.tenantId())
                        .addValue("appointment_id", record.appointmentId())
                        .addValue("patient_id", record.patientId())
                        .addValue("medical_record_id", record.medicalRecordId())
                        .addValue("branch_id", record.branchId())
                        .addValue("veterinarian_id", record.veterinarianId())
                        .addValue("started_at", Timestamp.from(record.startedAt()))
                        .addValue("status", record.status())
                        .addValue("created_by", record.createdBy())
                        .addValue("created_at", Timestamp.from(record.createdAt()))
                        .addValue("updated_at", Timestamp.from(record.updatedAt())));

        return new ConsultationSummary(
                record.id(),
                record.appointmentId(),
                record.appointmentStatus(),
                record.patientId(),
                record.patientName(),
                record.medicalRecordId(),
                record.branchId(),
                record.branchName(),
                record.veterinarianId(),
                record.veterinarianFullName(),
                record.status(),
                record.startedAt(),
                null,
                null,
                null,
                null,
                null,
                record.createdAt(),
                record.updatedAt());
    }

    @Override
    public ConsultationSummary updateEvaluation(UpdateEvaluationRecord record) {
        String sql = """
                UPDATE consultations
                SET reason = :reason,
                    anamnesis = :anamnesis,
                    physical_exam = :physical_exam,
                    clinical_details = CAST(:clinical_details AS jsonb),
                    updated_at = :updated_at
                WHERE tenant_id = :tenant_id
                  AND id = :id
                  AND status = 'BORRADOR'
                """;
        int rows = jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", record.tenantId())
                        .addValue("id", record.consultationId())
                        .addValue("reason", record.reason())
                        .addValue("anamnesis", record.anamnesis())
                        .addValue("physical_exam", record.physicalExam())
                        .addValue("clinical_details", serializeClinicalDetails(record.clinicalDetails()), Types.OTHER)
                        .addValue("updated_at", Timestamp.from(record.updatedAt())));
        if (rows == 0) {
            Optional<ConsultationSummary> existing = findById(record.tenantId(), record.consultationId());
            if (existing.isEmpty()) {
                throw new ConsultationResourceNotFoundException();
            }
            throw new InvalidConsultationStatusException(existing.get().status(), "BORRADOR");
        }
        return findById(record.tenantId(), record.consultationId())
                .orElseThrow(ConsultationResourceNotFoundException::new);
    }

    private ConsultationSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        ClinicalDetails details = parseClinicalDetails(rs.getString("clinical_details"));
        VitalSigns signs = details != null ? details.vitalSigns() : null;
        return new ConsultationSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("appointment_id", UUID.class),
                "EN_ATENCION",
                rs.getObject("patient_id", UUID.class),
                rs.getString("patient_name"),
                rs.getObject("medical_record_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getObject("veterinarian_id", UUID.class),
                rs.getString("veterinarian_full_name"),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getString("reason"),
                rs.getString("anamnesis"),
                rs.getString("physical_exam"),
                signs,
                details,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ClinicalDetails parseClinicalDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ClinicalDetails.class);
        } catch (Exception ex) {
            throw new IllegalStateException("No fue posible interpretar los detalles clínicos persistidos en la consulta: " + ex.getMessage(), ex);
        }
    }

    private String serializeClinicalDetails(ClinicalDetails details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot serialize clinical details", ex);
        }
    }

    @Override
    public List<DiagnosisSummary> findDiagnosesByConsultationId(UUID tenantId, UUID consultationId) {
        String sql = """
                SELECT
                    d.id,
                    d.consultation_id,
                    d.code,
                    d.description,
                    d.diagnosis_type,
                    d.notes,
                    d.created_at,
                    d.updated_at
                FROM diagnoses d
                WHERE d.tenant_id = :tenant_id
                  AND d.consultation_id = :consultation_id
                ORDER BY d.created_at ASC, d.id ASC
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId),
                this::mapDiagnosisRow);
    }

    @Override
    public Optional<DiagnosisSummary> findDiagnosisById(UUID tenantId, UUID consultationId, UUID diagnosisId) {
        String sql = """
                SELECT
                    d.id,
                    d.consultation_id,
                    d.code,
                    d.description,
                    d.diagnosis_type,
                    d.notes,
                    d.created_at,
                    d.updated_at
                FROM diagnoses d
                WHERE d.tenant_id = :tenant_id
                  AND d.consultation_id = :consultation_id
                  AND d.id = :diagnosis_id
                """;
        List<DiagnosisSummary> rows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId)
                        .addValue("diagnosis_id", diagnosisId),
                this::mapDiagnosisRow);
        return rows.stream().findFirst();
    }

    @Override
    public DiagnosisSummary insertDiagnosis(NewDiagnosisRecord record) {
        String sql = """
                INSERT INTO diagnoses (
                    id,
                    tenant_id,
                    consultation_id,
                    code,
                    description,
                    diagnosis_type,
                    notes,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :tenant_id,
                    :consultation_id,
                    :code,
                    :description,
                    :diagnosis_type,
                    :notes,
                    :created_at,
                    :updated_at
                )
                """;
        jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", record.id())
                        .addValue("tenant_id", record.tenantId())
                        .addValue("consultation_id", record.consultationId())
                        .addValue("code", record.code())
                        .addValue("description", record.description())
                        .addValue("diagnosis_type", record.diagnosisType())
                        .addValue("notes", record.notes())
                        .addValue("created_at", Timestamp.from(record.createdAt()))
                        .addValue("updated_at", Timestamp.from(record.updatedAt())));
        return new DiagnosisSummary(
                record.id(),
                record.consultationId(),
                record.code(),
                record.description(),
                record.diagnosisType(),
                record.notes(),
                record.createdAt(),
                record.updatedAt());
    }

    @Override
    public DiagnosisSummary updateDiagnosis(UpdateDiagnosisRecord record) {
        String sql = """
                UPDATE diagnoses
                SET code = :code,
                    description = :description,
                    diagnosis_type = :diagnosis_type,
                    notes = :notes,
                    updated_at = :updated_at
                WHERE tenant_id = :tenant_id
                  AND consultation_id = :consultation_id
                  AND id = :diagnosis_id
                """;
        int rows = jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", record.tenantId())
                        .addValue("consultation_id", record.consultationId())
                        .addValue("diagnosis_id", record.diagnosisId())
                        .addValue("code", record.code())
                        .addValue("description", record.description())
                        .addValue("diagnosis_type", record.diagnosisType())
                        .addValue("notes", record.notes())
                        .addValue("updated_at", Timestamp.from(record.updatedAt())));
        if (rows == 0) {
            throw new ConsultationResourceNotFoundException();
        }
        return findDiagnosisById(record.tenantId(), record.consultationId(), record.diagnosisId())
                .orElseThrow(ConsultationResourceNotFoundException::new);
    }

    @Override
    public List<PatientDiagnosisSummary> findDiagnosesByPatientId(UUID tenantId, UUID patientId) {
        String sql = """
                SELECT
                    d.id,
                    d.consultation_id,
                    c.started_at AS consultation_started_at,
                    c.reason AS consultation_reason,
                    d.code,
                    d.description,
                    d.diagnosis_type,
                    d.notes,
                    d.created_at
                FROM diagnoses d
                JOIN consultations c
                  ON c.tenant_id = d.tenant_id
                 AND c.id = d.consultation_id
                WHERE d.tenant_id = :tenant_id
                  AND c.patient_id = :patient_id
                ORDER BY d.created_at DESC, d.id DESC
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("patient_id", patientId),
                (rs, rowNum) -> new PatientDiagnosisSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("consultation_id", UUID.class),
                        rs.getTimestamp("consultation_started_at").toInstant(),
                        rs.getString("consultation_reason"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("diagnosis_type"),
                        rs.getString("notes"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private DiagnosisSummary mapDiagnosisRow(ResultSet rs, int rowNum) throws SQLException {
        return new DiagnosisSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("consultation_id", UUID.class),
                rs.getString("code"),
                rs.getString("description"),
                rs.getString("diagnosis_type"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
