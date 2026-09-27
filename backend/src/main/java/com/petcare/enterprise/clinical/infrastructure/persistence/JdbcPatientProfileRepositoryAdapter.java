package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.petcare.enterprise.clinical.application.PatientProfileRepository;
import com.petcare.enterprise.clinical.application.PatientProfileUseCase.MedicalRecordSummary;
import com.petcare.enterprise.clinical.application.PatientProfileUseCase.OwnerSummary;
import com.petcare.enterprise.clinical.application.PatientProfileUseCase.PatientProfile;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPatientProfileRepositoryAdapter implements PatientProfileRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcPatientProfileRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PatientProfile> findById(UUID tenantId, UUID patientId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("patientId", patientId);

        List<PatientProfile> rows = jdbc.query("""
                SELECT p.id,
                       p.name,
                       p.species,
                       p.breed,
                       p.sex,
                       p.birth_date,
                       p.color,
                       p.microchip,
                       p.sterilized,
                       p.status,
                       p.created_at,
                       o.id AS owner_id,
                       trim(o.first_name || ' ' || o.last_name) AS owner_full_name,
                       o.document_type AS owner_document_type,
                       o.document_number AS owner_document_number,
                       o.phone AS owner_phone,
                       o.email AS owner_email,
                       mr.id AS medical_record_id,
                       mr.allergies AS medical_record_allergies,
                       mr.medical_alerts AS medical_record_alerts,
                       mr.history AS medical_record_history,
                       mr.created_at AS medical_record_created_at
                FROM patients p
                JOIN owners o ON o.tenant_id = p.tenant_id AND o.id = p.owner_id
                LEFT JOIN medical_records mr ON mr.tenant_id = p.tenant_id AND mr.patient_id = p.id
                WHERE p.tenant_id = :tenantId
                  AND o.tenant_id = :tenantId
                  AND p.id = :patientId
                """, parameters, this::map);

        return rows.stream().findFirst();
    }

    private PatientProfile map(ResultSet rs, int rowNum) throws SQLException {
        Date birthDate = rs.getDate("birth_date");
        Boolean sterilized = (Boolean) rs.getObject("sterilized");
        Timestamp createdAt = rs.getTimestamp("created_at");

        OwnerSummary owner = new OwnerSummary(
                rs.getObject("owner_id", UUID.class),
                rs.getString("owner_full_name"),
                rs.getString("owner_document_type"),
                rs.getString("owner_document_number"),
                rs.getString("owner_phone"),
                rs.getString("owner_email"));

        UUID medicalRecordId = rs.getObject("medical_record_id", UUID.class);
        Timestamp mrCreatedAt = rs.getTimestamp("medical_record_created_at");
        MedicalRecordSummary medicalRecord = medicalRecordId == null
                ? null
                : new MedicalRecordSummary(
                        medicalRecordId,
                        rs.getString("medical_record_allergies"),
                        rs.getString("medical_record_alerts"),
                        rs.getString("medical_record_history"),
                        mrCreatedAt == null ? null : mrCreatedAt.toInstant());

        return new PatientProfile(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("species"),
                rs.getString("breed"),
                rs.getString("sex"),
                birthDate == null ? null : birthDate.toLocalDate(),
                rs.getString("color"),
                rs.getString("microchip"),
                sterilized,
                rs.getString("status"),
                createdAt == null ? null : createdAt.toInstant(),
                owner,
                medicalRecord);
    }
}
