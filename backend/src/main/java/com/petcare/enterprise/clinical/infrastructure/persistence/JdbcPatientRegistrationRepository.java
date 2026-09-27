package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.petcare.enterprise.clinical.application.PatientRegistrationRepository;
import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase;
import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase.NewPatient;
import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase.Patient;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPatientRegistrationRepository implements PatientRegistrationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcPatientRegistrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean activeOwnerExists(UUID tenantId, UUID ownerId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM owners WHERE tenant_id=:tenantId AND id=:ownerId AND active=true",
                Map.of("tenantId", tenantId, "ownerId", ownerId),
                Integer.class);
        return found != null && found > 0;
    }

    @Override
    public Optional<Patient> findById(UUID tenantId, UUID patientId) {
        List<Patient> rows = jdbc.query("""
                SELECT id, owner_id, name, species, breed, sex, birth_date, color, microchip, sterilized
                FROM patients
                WHERE tenant_id = :tenantId AND id = :patientId
                """,
                Map.of("tenantId", tenantId, "patientId", patientId),
                this::mapPatient);
        return rows.stream().findFirst();
    }

    @Override
    public boolean microchipExistsExcluding(UUID tenantId, String microchip, UUID excludedPatientId) {
        if (excludedPatientId == null) {
            Integer found = jdbc.queryForObject(
                    "SELECT count(*) FROM patients WHERE tenant_id = :tenantId AND microchip = :microchip",
                    Map.of("tenantId", tenantId, "microchip", microchip),
                    Integer.class);
            return found != null && found > 0;
        }
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM patients
                WHERE tenant_id = :tenantId AND microchip = :microchip AND id <> :excludedId
                """,
                Map.of("tenantId", tenantId, "microchip", microchip, "excludedId", excludedPatientId),
                Integer.class);
        return found != null && found > 0;
    }

    @Override
    public Patient createPatientAndMedicalRecord(UUID tenantId, NewPatient patient) {
        UUID patientId = UUID.randomUUID();
        UUID medicalRecordId = UUID.randomUUID();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", patientId)
                .addValue("tenantId", tenantId)
                .addValue("ownerId", patient.ownerId())
                .addValue("name", patient.name())
                .addValue("species", patient.species())
                .addValue("breed", patient.breed())
                .addValue("sex", patient.sex())
                .addValue("birthDate", patient.birthDate())
                .addValue("color", patient.color())
                .addValue("microchip", patient.microchip())
                .addValue("sterilized", patient.sterilized());
        jdbc.update("""
                INSERT INTO patients (id,tenant_id,owner_id,name,species,breed,sex,birth_date,color,microchip,sterilized,status)
                VALUES (:id,:tenantId,:ownerId,:name,:species,:breed,:sex,:birthDate,:color,:microchip,:sterilized,'ACTIVO')
                """, p);
        jdbc.update(
                "INSERT INTO medical_records (id,tenant_id,patient_id) VALUES (:id,:tenantId,:patientId)",
                new MapSqlParameterSource()
                        .addValue("id", medicalRecordId)
                        .addValue("tenantId", tenantId)
                        .addValue("patientId", patientId));
        return new Patient(
                patientId,
                patient.ownerId(),
                patient.name(),
                patient.species(),
                patient.breed(),
                patient.sex(),
                patient.birthDate(),
                patient.color(),
                patient.microchip(),
                patient.sterilized());
    }

    @Override
    public Patient update(UUID tenantId, UUID patientId, NewPatient patient) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", patientId)
                .addValue("tenantId", tenantId)
                .addValue("ownerId", patient.ownerId())
                .addValue("name", patient.name())
                .addValue("species", patient.species())
                .addValue("breed", patient.breed())
                .addValue("sex", patient.sex())
                .addValue("birthDate", patient.birthDate())
                .addValue("color", patient.color())
                .addValue("microchip", patient.microchip())
                .addValue("sterilized", patient.sterilized());
        jdbc.update("""
                UPDATE patients
                SET owner_id = :ownerId,
                    name = :name,
                    species = :species,
                    breed = :breed,
                    sex = :sex,
                    birth_date = :birthDate,
                    color = :color,
                    microchip = :microchip,
                    sterilized = :sterilized
                WHERE tenant_id = :tenantId AND id = :id
                """, p);
        return new Patient(
                patientId,
                patient.ownerId(),
                patient.name(),
                patient.species(),
                patient.breed(),
                patient.sex(),
                patient.birthDate(),
                patient.color(),
                patient.microchip(),
                patient.sterilized());
    }

    private Patient mapPatient(ResultSet rs, int rowNum) throws SQLException {
        Date birthDate = rs.getDate("birth_date");
        return new Patient(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("name"),
                rs.getString("species"),
                rs.getString("breed"),
                rs.getString("sex"),
                birthDate == null ? null : birthDate.toLocalDate(),
                rs.getString("color"),
                rs.getString("microchip"),
                (Boolean) rs.getObject("sterilized"));
    }
}
