package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.petcare.enterprise.clinical.application.PatientRegistrationRepository;
import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPatientRegistrationRepository implements PatientRegistrationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    JdbcPatientRegistrationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean activeOwnerExists(UUID tenantId, UUID ownerId) { Integer found=jdbc.queryForObject("SELECT count(*) FROM owners WHERE tenant_id=:tenantId AND id=:ownerId AND active=true", Map.of("tenantId",tenantId,"ownerId",ownerId), Integer.class); return found != null && found > 0; }
    @Override public PatientRegistrationUseCase.Patient createPatientAndMedicalRecord(UUID tenantId, PatientRegistrationUseCase.NewPatient patient) {
        UUID patientId=UUID.randomUUID(), medicalRecordId=UUID.randomUUID();
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",patientId).addValue("tenantId",tenantId).addValue("ownerId",patient.ownerId()).addValue("name",patient.name()).addValue("species",patient.species()).addValue("breed",patient.breed()).addValue("sex",patient.sex()).addValue("birthDate",patient.birthDate()).addValue("color",patient.color()).addValue("microchip",patient.microchip()).addValue("sterilized",patient.sterilized());
        jdbc.update("INSERT INTO patients (id,tenant_id,owner_id,name,species,breed,sex,birth_date,color,microchip,sterilized,status) VALUES (:id,:tenantId,:ownerId,:name,:species,:breed,:sex,:birthDate,:color,:microchip,:sterilized,'ACTIVO')",p);
        jdbc.update("INSERT INTO medical_records (id,tenant_id,patient_id) VALUES (:id,:tenantId,:patientId)",new MapSqlParameterSource().addValue("id",medicalRecordId).addValue("tenantId",tenantId).addValue("patientId",patientId));
        return new PatientRegistrationUseCase.Patient(patientId,patient.ownerId(),patient.name(),patient.species(),patient.breed(),patient.sex(),patient.birthDate(),patient.color(),patient.microchip(),patient.sterilized());
    }
}
