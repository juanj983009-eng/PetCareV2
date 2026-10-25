package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.enterprise.clinical.application.ConsultationRepository;
import com.petcare.enterprise.clinical.application.ConsultationResourceNotFoundException;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.ConsultationSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationDiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationItemSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPage;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPrescriptionItemSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPrescriptionSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionItemSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionSummary;
import com.petcare.enterprise.clinical.application.InvalidConsultationStatusException;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.VitalSigns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                    c.updated_at,
                    c.finalized_at
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
                    c.updated_at,
                    c.finalized_at
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
                    c.updated_at,
                    c.finalized_at
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
                record.updatedAt(),
                null);
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

    @Override
    public ConsultationSummary finalizeConsultation(UUID tenantId, UUID consultationId, Instant finalizedAt) {
        String sql = """
                UPDATE consultations
                SET status = 'FINALIZADA',
                    finalized_at = :finalized_at,
                    updated_at = :updated_at
                WHERE tenant_id = :tenant_id
                  AND id = :consultation_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant_id", tenantId)
                .addValue("consultation_id", consultationId)
                .addValue("finalized_at", Timestamp.from(finalizedAt))
                .addValue("updated_at", Timestamp.from(finalizedAt));
        int rows = jdbc.update(sql, params);
        if (rows == 0) {
            throw new ConsultationResourceNotFoundException();
        }
        return findById(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);
    }

    private ConsultationSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        ClinicalDetails details = parseClinicalDetails(rs.getString("clinical_details"));
        VitalSigns signs = details != null ? details.vitalSigns() : null;
        Timestamp finalizedTs = rs.getTimestamp("finalized_at");
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
                rs.getTimestamp("updated_at").toInstant(),
                finalizedTs != null ? finalizedTs.toInstant() : null);
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

    @Override
    public PatientConsultationPage findConsultationsByPatientId(UUID tenantId, UUID patientId, int page, int size) {
        String countSql = "SELECT COUNT(*) FROM consultations WHERE tenant_id = :tenant_id AND patient_id = :patient_id";
        Long totalElements = jdbc.queryForObject(
                countSql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("patient_id", patientId),
                Long.class);
        if (totalElements == null) {
            totalElements = 0L;
        }
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / (double) size) : 0;
        if (totalElements == 0) {
            return new PatientConsultationPage(List.of(), page, size, 0L, 0);
        }

        int offset = page * size;
        String consultationsSql = """
                SELECT
                    c.id,
                    c.appointment_id,
                    c.started_at,
                    c.status,
                    COALESCE(c.reason, a.reason) AS reason,
                    c.veterinarian_id,
                    btrim(v.first_name || ' ' || v.last_name) AS veterinarian_full_name,
                    c.branch_id,
                    b.name AS branch_name
                FROM consultations c
                JOIN users v
                  ON v.tenant_id = c.tenant_id
                 AND v.id = c.veterinarian_id
                JOIN branches b
                  ON b.tenant_id = c.tenant_id
                 AND b.id = c.branch_id
                LEFT JOIN appointments a
                  ON a.tenant_id = c.tenant_id
                 AND a.id = c.appointment_id
                WHERE c.tenant_id = :tenant_id
                  AND c.patient_id = :patient_id
                ORDER BY c.started_at DESC, c.id DESC
                LIMIT :limit OFFSET :offset
                """;

        List<PatientConsultationRow> consultationRows = jdbc.query(
                consultationsSql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("patient_id", patientId)
                        .addValue("limit", size)
                        .addValue("offset", offset),
                (rs, rowNum) -> new PatientConsultationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("appointment_id", UUID.class),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getString("status"),
                        rs.getString("reason"),
                        rs.getObject("veterinarian_id", UUID.class),
                        rs.getString("veterinarian_full_name"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name")));

        if (consultationRows.isEmpty()) {
            return new PatientConsultationPage(List.of(), page, size, totalElements, totalPages);
        }

        List<UUID> consultationIds = consultationRows.stream().map(PatientConsultationRow::id).toList();

        String diagnosesSql = """
                SELECT
                    d.id,
                    d.consultation_id,
                    d.code,
                    d.description,
                    d.diagnosis_type
                FROM diagnoses d
                WHERE d.tenant_id = :tenant_id
                  AND d.consultation_id IN (:consultation_ids)
                ORDER BY d.consultation_id, d.created_at ASC, d.id ASC
                """;

        Map<UUID, List<PatientConsultationDiagnosisSummary>> diagnosesByConsultationId = new HashMap<>();
        jdbc.query(
                diagnosesSql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_ids", consultationIds),
                rs -> {
                    UUID cid = rs.getObject("consultation_id", UUID.class);
                    diagnosesByConsultationId.computeIfAbsent(cid, k -> new ArrayList<>())
                            .add(new PatientConsultationDiagnosisSummary(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("code"),
                                    rs.getString("description"),
                                    rs.getString("diagnosis_type")));
                });

        String prescriptionsSql = """
                SELECT
                    p.id AS prescription_id,
                    p.consultation_id,
                    p.status,
                    p.issued_at,
                    pi.id AS item_id,
                    pi.medication_name,
                    pi.dosage,
                    pi.frequency,
                    pi.duration,
                    pi.route
                FROM prescriptions p
                LEFT JOIN prescription_items pi
                  ON pi.tenant_id = p.tenant_id
                 AND pi.prescription_id = p.id
                WHERE p.tenant_id = :tenant_id
                  AND p.consultation_id IN (:consultation_ids)
                ORDER BY p.consultation_id, p.created_at ASC, p.id ASC, pi.item_order ASC, pi.id ASC
                """;

        Map<UUID, LinkedHashMap<UUID, PatientPrescriptionBuilder>> prescriptionsByConsultation = new LinkedHashMap<>();
        jdbc.query(
                prescriptionsSql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_ids", consultationIds),
                rs -> {
                    UUID cid = rs.getObject("consultation_id", UUID.class);
                    UUID pid = rs.getObject("prescription_id", UUID.class);
                    String status = rs.getString("status");
                    Timestamp issuedTimestamp = rs.getTimestamp("issued_at");
                    Instant issuedAt = issuedTimestamp != null ? issuedTimestamp.toInstant() : null;

                    LinkedHashMap<UUID, PatientPrescriptionBuilder> map =
                            prescriptionsByConsultation.computeIfAbsent(cid, k -> new LinkedHashMap<>());
                    PatientPrescriptionBuilder builder =
                            map.computeIfAbsent(pid, k -> new PatientPrescriptionBuilder(pid, status, issuedAt));

                    UUID itemId = rs.getObject("item_id", UUID.class);
                    if (itemId != null) {
                        builder.items.add(new PatientConsultationPrescriptionItemSummary(
                                itemId,
                                rs.getString("medication_name"),
                                rs.getString("dosage"),
                                rs.getString("frequency"),
                                rs.getString("duration"),
                                rs.getString("route")));
                    }
                });

        List<PatientConsultationItemSummary> items = new ArrayList<>(consultationRows.size());
        for (PatientConsultationRow row : consultationRows) {
            List<PatientConsultationDiagnosisSummary> diagList =
                    diagnosesByConsultationId.getOrDefault(row.id(), List.of());
            LinkedHashMap<UUID, PatientPrescriptionBuilder> prescribers =
                    prescriptionsByConsultation.get(row.id());
            List<PatientConsultationPrescriptionSummary> prescList = prescribers == null
                    ? List.of()
                    : prescribers.values().stream().map(PatientPrescriptionBuilder::toSummary).toList();

            items.add(new PatientConsultationItemSummary(
                    row.id(),
                    row.appointmentId(),
                    row.startedAt(),
                    row.status(),
                    row.reason(),
                    row.veterinarianId(),
                    row.veterinarianFullName(),
                    row.branchId(),
                    row.branchName(),
                    diagList,
                    prescList));
        }

        return new PatientConsultationPage(items, page, size, totalElements, totalPages);
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

    @Override
    public List<PrescriptionSummary> findPrescriptionsByConsultationId(UUID tenantId, UUID consultationId) {
        String sql = """
                SELECT
                    p.id,
                    p.consultation_id,
                    p.prescribed_by,
                    btrim(u.first_name || ' ' || u.last_name) AS veterinarian_full_name,
                    p.issued_at,
                    p.general_instructions,
                    p.status,
                    p.created_at
                FROM prescriptions p
                JOIN users u
                  ON u.tenant_id = p.tenant_id
                 AND u.id = p.prescribed_by
                WHERE p.tenant_id = :tenant_id
                  AND p.consultation_id = :consultation_id
                ORDER BY p.created_at ASC, p.id ASC
                """;
        List<PrescriptionHeader> headers = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId),
                this::mapPrescriptionHeader);

        List<PrescriptionSummary> result = new java.util.ArrayList<>(headers.size());
        for (PrescriptionHeader header : headers) {
            List<PrescriptionItemSummary> items = findItemsByPrescriptionId(tenantId, header.id());
            result.add(new PrescriptionSummary(
                    header.id(),
                    header.consultationId(),
                    header.prescribedBy(),
                    header.veterinarianFullName(),
                    header.issuedAt(),
                    header.generalInstructions(),
                    header.status(),
                    items,
                    header.createdAt()));
        }
        return result;
    }

    @Override
    public Optional<PrescriptionSummary> findPrescriptionByIdempotencyKey(
            UUID tenantId, UUID consultationId, String idempotencyKey) {
        String sql = """
                SELECT
                    p.id,
                    p.consultation_id,
                    p.prescribed_by,
                    btrim(u.first_name || ' ' || u.last_name) AS veterinarian_full_name,
                    p.issued_at,
                    p.general_instructions,
                    p.status,
                    p.created_at
                FROM prescriptions p
                JOIN users u
                  ON u.tenant_id = p.tenant_id
                 AND u.id = p.prescribed_by
                WHERE p.tenant_id = :tenant_id
                  AND p.consultation_id = :consultation_id
                  AND p.idempotency_key = :idempotency_key
                """;
        List<PrescriptionHeader> headers = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId)
                        .addValue("idempotency_key", idempotencyKey),
                this::mapPrescriptionHeader);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        PrescriptionHeader header = headers.get(0);
        List<PrescriptionItemSummary> items = findItemsByPrescriptionId(tenantId, header.id());
        return Optional.of(new PrescriptionSummary(
                header.id(),
                header.consultationId(),
                header.prescribedBy(),
                header.veterinarianFullName(),
                header.issuedAt(),
                header.generalInstructions(),
                header.status(),
                items,
                header.createdAt()));
    }

    @Override
    public Optional<PrescriptionSummary> findPrescriptionById(
            UUID tenantId, UUID consultationId, UUID prescriptionId) {
        String sql = """
                SELECT
                    p.id,
                    p.consultation_id,
                    p.prescribed_by,
                    btrim(u.first_name || ' ' || u.last_name) AS veterinarian_full_name,
                    p.issued_at,
                    p.general_instructions,
                    p.status,
                    p.created_at
                FROM prescriptions p
                JOIN users u
                  ON u.tenant_id = p.tenant_id
                 AND u.id = p.prescribed_by
                WHERE p.tenant_id = :tenant_id
                  AND p.consultation_id = :consultation_id
                  AND p.id = :prescription_id
                """;
        List<PrescriptionHeader> headers = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("consultation_id", consultationId)
                        .addValue("prescription_id", prescriptionId),
                this::mapPrescriptionHeader);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        PrescriptionHeader header = headers.get(0);
        List<PrescriptionItemSummary> items = findItemsByPrescriptionId(tenantId, header.id());
        return Optional.of(new PrescriptionSummary(
                header.id(),
                header.consultationId(),
                header.prescribedBy(),
                header.veterinarianFullName(),
                header.issuedAt(),
                header.generalInstructions(),
                header.status(),
                items,
                header.createdAt()));
    }

    @Override
    public PrescriptionSummary insertPrescription(
            NewPrescriptionRecord record, List<NewPrescriptionItemRecord> items) {
        String insertPrescriptionSql = """
                INSERT INTO prescriptions (
                    id,
                    tenant_id,
                    consultation_id,
                    prescribed_by,
                    issued_at,
                    general_instructions,
                    status,
                    idempotency_key,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :tenant_id,
                    :consultation_id,
                    :prescribed_by,
                    :issued_at,
                    :general_instructions,
                    :status,
                    :idempotency_key,
                    :created_at,
                    :updated_at
                )
                """;
        jdbc.update(
                insertPrescriptionSql,
                new MapSqlParameterSource()
                        .addValue("id", record.id())
                        .addValue("tenant_id", record.tenantId())
                        .addValue("consultation_id", record.consultationId())
                        .addValue("prescribed_by", record.prescribedBy())
                        .addValue("issued_at", Timestamp.from(record.issuedAt()))
                        .addValue("general_instructions", record.generalInstructions())
                        .addValue("status", record.status())
                        .addValue("idempotency_key", record.idempotencyKey())
                        .addValue("created_at", Timestamp.from(record.createdAt()))
                        .addValue("updated_at", Timestamp.from(record.updatedAt())));

        String insertItemSql = """
                INSERT INTO prescription_items (
                    id,
                    tenant_id,
                    prescription_id,
                    item_order,
                    medication_name,
                    dosage,
                    frequency,
                    duration,
                    route,
                    instructions,
                    created_at
                ) VALUES (
                    :id,
                    :tenant_id,
                    :prescription_id,
                    :item_order,
                    :medication_name,
                    :dosage,
                    :frequency,
                    :duration,
                    :route,
                    :instructions,
                    :created_at
                )
                """;
        List<PrescriptionItemSummary> summaryItems = new java.util.ArrayList<>(items.size());
        for (NewPrescriptionItemRecord item : items) {
            jdbc.update(
                    insertItemSql,
                    new MapSqlParameterSource()
                            .addValue("id", item.id())
                            .addValue("tenant_id", item.tenantId())
                            .addValue("prescription_id", item.prescriptionId())
                            .addValue("item_order", item.itemOrder())
                            .addValue("medication_name", item.medicationName())
                            .addValue("dosage", item.dosage())
                            .addValue("frequency", item.frequency())
                            .addValue("duration", item.duration())
                            .addValue("route", item.route())
                            .addValue("instructions", item.instructions())
                            .addValue("created_at", Timestamp.from(item.createdAt())));

            summaryItems.add(new PrescriptionItemSummary(
                    item.id(),
                    item.prescriptionId(),
                    item.itemOrder(),
                    item.medicationName(),
                    item.dosage(),
                    item.frequency(),
                    item.duration(),
                    item.route(),
                    item.instructions(),
                    item.createdAt()));
        }

        return new PrescriptionSummary(
                record.id(),
                record.consultationId(),
                record.prescribedBy(),
                record.veterinarianFullName(),
                record.issuedAt(),
                record.generalInstructions(),
                record.status(),
                summaryItems,
                record.createdAt());
    }

    private List<PrescriptionItemSummary> findItemsByPrescriptionId(UUID tenantId, UUID prescriptionId) {
        String sql = """
                SELECT
                    i.id,
                    i.prescription_id,
                    i.item_order,
                    i.medication_name,
                    i.dosage,
                    i.frequency,
                    i.duration,
                    i.route,
                    i.instructions,
                    i.created_at
                FROM prescription_items i
                WHERE i.tenant_id = :tenant_id
                  AND i.prescription_id = :prescription_id
                ORDER BY i.item_order ASC, i.id ASC
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenant_id", tenantId)
                        .addValue("prescription_id", prescriptionId),
                (rs, rowNum) -> new PrescriptionItemSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("prescription_id", UUID.class),
                        rs.getInt("item_order"),
                        rs.getString("medication_name"),
                        rs.getString("dosage"),
                        rs.getString("frequency"),
                        rs.getString("duration"),
                        rs.getString("route"),
                        rs.getString("instructions"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private PrescriptionHeader mapPrescriptionHeader(ResultSet rs, int rowNum) throws SQLException {
        return new PrescriptionHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("consultation_id", UUID.class),
                rs.getObject("prescribed_by", UUID.class),
                rs.getString("veterinarian_full_name"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getString("general_instructions"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record PrescriptionHeader(
            UUID id,
            UUID consultationId,
            UUID prescribedBy,
            String veterinarianFullName,
            Instant issuedAt,
            String generalInstructions,
            String status,
            Instant createdAt) {
    }

    private record PatientConsultationRow(
            UUID id,
            UUID appointmentId,
            Instant startedAt,
            String status,
            String reason,
            UUID veterinarianId,
            String veterinarianFullName,
            UUID branchId,
            String branchName) {
    }

    private static class PatientPrescriptionBuilder {
        final UUID id;
        final String status;
        final Instant issuedAt;
        final List<PatientConsultationPrescriptionItemSummary> items = new ArrayList<>();

        PatientPrescriptionBuilder(UUID id, String status, Instant issuedAt) {
            this.id = id;
            this.status = status;
            this.issuedAt = issuedAt;
        }

        PatientConsultationPrescriptionSummary toSummary() {
            return new PatientConsultationPrescriptionSummary(id, status, issuedAt, List.copyOf(items));
        }
    }
}
