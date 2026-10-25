package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway;
import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway.AppointmentConsultationContext;
import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway.StartAppointmentAttentionCommand;
import com.petcare.enterprise.appointments.application.InvalidAppointmentScheduleQueryException;
import com.petcare.enterprise.appointments.application.InvalidAppointmentStatusTransitionException;
import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.ActivePatientMedicalRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.NewConsultationRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.NewDiagnosisRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.UpdateDiagnosisRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.UpdateEvaluationRecord;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.AddDiagnosisCommand;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.UpdateDiagnosisCommand;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.ConsultationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationService implements ConsultationUseCase {

    private final AppointmentConsultationGateway appointmentGateway;
    private final ConsultationRepository consultationRepository;
    private final AuditMetadataProvider audit;
    private final AuditLogWriter auditWriter;
    private final Clock clock;

    public ConsultationService(
            AppointmentConsultationGateway appointmentGateway,
            ConsultationRepository consultationRepository,
            AuditMetadataProvider audit,
            AuditLogWriter auditWriter,
            Clock clock) {
        this.appointmentGateway = Objects.requireNonNull(appointmentGateway, "appointmentGateway is required");
        this.consultationRepository = Objects.requireNonNull(consultationRepository, "consultationRepository is required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional
    public StartConsultationResult startFromAppointment(UUID appointmentId) {
        if (appointmentId == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "appointmentId",
                    "El identificador de la cita es obligatorio.");
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        AppointmentConsultationContext appointment = appointmentGateway
                .findForConsultation(tenantId, appointmentId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        if (!appointment.veterinarianId().equals(actorUserId) || !appointment.veterinarianActiveAndAssigned()) {
            throw new ConsultationAccessDeniedException(
                    "Solo el veterinario activo asignado a la cita en la sucursal puede iniciar la consulta.");
        }

        if (!appointment.branchActive()) {
            throw new ConsultationResourceNotFoundException();
        }

        ActivePatientMedicalRecord patientRecord = consultationRepository
                .findActivePatientMedicalRecord(tenantId, appointment.patientId())
                .orElseThrow(ConsultationResourceNotFoundException::new);

        Optional<ConsultationSummary> existing = consultationRepository.findByAppointmentId(tenantId, appointmentId);
        if (existing.isPresent()) {
            AppointmentConsultationContext latest = "EN_ATENCION".equals(appointment.status())
                    ? appointment
                    : appointmentGateway.findForConsultation(tenantId, appointmentId)
                            .orElseThrow(ConsultationResourceNotFoundException::new);
            if (!"EN_ATENCION".equals(latest.status())) {
                throw new InvalidAppointmentStatusTransitionException(latest.status(), "EN_ATENCION");
            }
            return new StartConsultationResult(existing.get(), false);
        }

        if (!"EN_ESPERA".equals(appointment.status())) {
            throw new InvalidAppointmentStatusTransitionException(appointment.status(), "EN_ATENCION");
        }

        Instant now = clock.instant();
        boolean transitioned = appointmentGateway.startAttention(new StartAppointmentAttentionCommand(
                tenantId,
                appointment.id(),
                appointment.branchId(),
                actorUserId,
                now,
                metadata.correlationId()));

        if (!transitioned) {
            Optional<ConsultationSummary> concurrentExisting = consultationRepository.findByAppointmentId(
                    tenantId,
                    appointmentId);
            if (concurrentExisting.isPresent()) {
                return new StartConsultationResult(concurrentExisting.get(), false);
            }
            AppointmentConsultationContext reloaded = appointmentGateway
                    .findForConsultation(tenantId, appointmentId)
                    .orElseThrow(ConsultationResourceNotFoundException::new);
            throw new InvalidAppointmentStatusTransitionException(reloaded.status(), "EN_ATENCION");
        }

        UUID consultationId = UUID.randomUUID();
        ConsultationSummary created = consultationRepository.insert(new NewConsultationRecord(
                consultationId,
                tenantId,
                appointment.id(),
                "EN_ATENCION",
                patientRecord.patientId(),
                patientRecord.patientName(),
                patientRecord.medicalRecordId(),
                appointment.branchId(),
                appointment.branchName(),
                appointment.veterinarianId(),
                appointment.veterinarianFullName(),
                ConsultationStatus.BORRADOR.name(),
                now,
                actorUserId,
                now,
                now));

        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                created.branchId(),
                "CONSULTATION",
                created.id(),
                "CONSULTATION_STARTED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "consultationId", created.id().toString(),
                        "appointmentId", created.appointmentId().toString(),
                        "patientId", created.patientId().toString(),
                        "medicalRecordId", created.medicalRecordId().toString(),
                        "branchId", created.branchId().toString(),
                        "veterinarianId", created.veterinarianId().toString(),
                        "status", created.status())));

        return new StartConsultationResult(created, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationSummary getById(UUID consultationId) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        return consultationRepository.findById(metadata.tenantId(), consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);
    }

    @Override
    @Transactional
    public ConsultationSummary saveEvaluation(UUID consultationId, SaveEvaluationCommand command) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        if (command == null) {
            throw new InvalidConsultationDataException("command", "Los datos de la evaluación clínica son obligatorios.");
        }

        if (command.reason() == null || command.reason().isBlank()) {
            throw new InvalidConsultationDataException("reason", "El motivo de la consulta es obligatorio.");
        }
        String normalizedReason = command.reason().trim();
        if (normalizedReason.length() > 500) {
            throw new InvalidConsultationDataException("reason", "El motivo no debe exceder los 500 caracteres.");
        }

        String normalizedAnamnesis = null;
        if (command.anamnesis() != null) {
            String trimmed = command.anamnesis().trim();
            if (trimmed.length() > 4000) {
                throw new InvalidConsultationDataException("anamnesis", "La anamnesis no debe exceder los 4000 caracteres.");
            }
            normalizedAnamnesis = trimmed.isEmpty() ? null : trimmed;
        }

        String normalizedPhysicalExam = null;
        if (command.physicalExam() != null) {
            String trimmed = command.physicalExam().trim();
            if (trimmed.length() > 4000) {
                throw new InvalidConsultationDataException("physicalExam", "El examen físico no debe exceder los 4000 caracteres.");
            }
            normalizedPhysicalExam = trimmed.isEmpty() ? null : trimmed;
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        ConsultationSummary consultation = consultationRepository.lockConsultationForUpdate(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        if (!consultation.veterinarianId().equals(actorUserId)) {
            throw new ConsultationAccessDeniedException(
                    "Solo el veterinario asignado a la consulta puede registrar o modificar la evaluación clínica.");
        }

        if (!"BORRADOR".equals(consultation.status())) {
            throw new InvalidConsultationStatusException(consultation.status(), "BORRADOR");
        }

        Map<String, String> resolvedVariableDetails;
        if (command.variableDetails() != null) {
            resolvedVariableDetails = command.variableDetails();
        } else if (consultation.clinicalDetails() != null && consultation.clinicalDetails().variableDetails() != null) {
            resolvedVariableDetails = consultation.clinicalDetails().variableDetails();
        } else {
            resolvedVariableDetails = Map.of();
        }

        ClinicalDetails clinicalDetails = new ClinicalDetails(
                command.vitalSigns(),
                command.observations(),
                resolvedVariableDetails);

        Instant now = clock.instant();
        ConsultationSummary updated = consultationRepository.updateEvaluation(new UpdateEvaluationRecord(
                tenantId,
                consultationId,
                normalizedReason,
                normalizedAnamnesis,
                normalizedPhysicalExam,
                clinicalDetails,
                now));

        // Audit log without copying sensitive clinical contents (anamnesis, physical exam, vital signs, etc.)
        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                consultation.branchId(),
                "CONSULTATION",
                consultationId,
                "CONSULTATION_EVALUATION_SAVED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "consultationId", consultationId.toString(),
                        "appointmentId", consultation.appointmentId().toString(),
                        "patientId", consultation.patientId().toString(),
                        "branchId", consultation.branchId().toString(),
                        "veterinarianId", consultation.veterinarianId().toString(),
                        "status", consultation.status())));

        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagnosisSummary> listDiagnoses(UUID consultationId) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        consultationRepository.findById(metadata.tenantId(), consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);
        return consultationRepository.findDiagnosesByConsultationId(metadata.tenantId(), consultationId);
    }

    @Override
    @Transactional
    public DiagnosisSummary addDiagnosis(UUID consultationId, AddDiagnosisCommand command) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        if (command == null) {
            throw new InvalidConsultationDataException("command", "Los datos del diagnóstico son obligatorios.");
        }

        if (command.description() == null || command.description().isBlank()) {
            throw new InvalidConsultationDataException("description", "La descripción del diagnóstico es obligatoria.");
        }
        String normalizedDescription = command.description().trim();
        if (normalizedDescription.length() > 300) {
            throw new InvalidConsultationDataException("description", "La descripción no debe exceder los 300 caracteres.");
        }

        if (command.diagnosisType() == null || command.diagnosisType().isBlank()) {
            throw new InvalidConsultationDataException("diagnosisType", "El tipo de diagnóstico es obligatorio.");
        }
        String normalizedType = command.diagnosisType().trim().toUpperCase(Locale.ROOT);
        if (!"PRESUNTIVO".equals(normalizedType) && !"DEFINITIVO".equals(normalizedType)) {
            throw new InvalidConsultationDataException("diagnosisType", "El tipo de diagnóstico debe ser PRESUNTIVO o DEFINITIVO.");
        }

        String normalizedCode = null;
        if (command.code() != null && !command.code().isBlank()) {
            normalizedCode = command.code().trim();
            if (normalizedCode.length() > 50) {
                throw new InvalidConsultationDataException("code", "El código del diagnóstico no debe exceder los 50 caracteres.");
            }
        }

        String normalizedNotes = null;
        if (command.notes() != null && !command.notes().isBlank()) {
            normalizedNotes = command.notes().trim();
            if (normalizedNotes.length() > 2000) {
                throw new InvalidConsultationDataException("notes", "Las notas del diagnóstico no deben exceder los 2000 caracteres.");
            }
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        ConsultationSummary consultation = consultationRepository.lockConsultationForUpdate(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        if (!consultation.veterinarianId().equals(actorUserId)) {
            throw new ConsultationAccessDeniedException(
                    "Solo el veterinario asignado a la consulta puede registrar o modificar diagnósticos.");
        }

        if (!"BORRADOR".equals(consultation.status())) {
            throw new InvalidConsultationStatusException(consultation.status(), "BORRADOR");
        }

        UUID diagnosisId = UUID.randomUUID();
        Instant now = clock.instant();

        DiagnosisSummary created = consultationRepository.insertDiagnosis(new NewDiagnosisRecord(
                diagnosisId,
                tenantId,
                consultationId,
                normalizedCode,
                normalizedDescription,
                normalizedType,
                normalizedNotes,
                now,
                now));

        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                consultation.branchId(),
                "CONSULTATION",
                consultationId,
                "DIAGNOSIS_ADDED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "consultationId", consultationId.toString(),
                        "diagnosisId", diagnosisId.toString(),
                        "diagnosisType", normalizedType,
                        "hasCode", String.valueOf(normalizedCode != null),
                        "patientId", consultation.patientId().toString(),
                        "branchId", consultation.branchId().toString())));

        return created;
    }

    @Override
    @Transactional
    public DiagnosisSummary updateDiagnosis(UUID consultationId, UUID diagnosisId, UpdateDiagnosisCommand command) {
        if (consultationId == null || diagnosisId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        if (command == null) {
            throw new InvalidConsultationDataException("command", "Los datos del diagnóstico son obligatorios.");
        }

        if (command.description() == null || command.description().isBlank()) {
            throw new InvalidConsultationDataException("description", "La descripción del diagnóstico es obligatoria.");
        }
        String normalizedDescription = command.description().trim();
        if (normalizedDescription.length() > 300) {
            throw new InvalidConsultationDataException("description", "La descripción no debe exceder los 300 caracteres.");
        }

        if (command.diagnosisType() == null || command.diagnosisType().isBlank()) {
            throw new InvalidConsultationDataException("diagnosisType", "El tipo de diagnóstico es obligatorio.");
        }
        String normalizedType = command.diagnosisType().trim().toUpperCase(Locale.ROOT);
        if (!"PRESUNTIVO".equals(normalizedType) && !"DEFINITIVO".equals(normalizedType)) {
            throw new InvalidConsultationDataException("diagnosisType", "El tipo de diagnóstico debe ser PRESUNTIVO o DEFINITIVO.");
        }

        String normalizedCode = null;
        if (command.code() != null && !command.code().isBlank()) {
            normalizedCode = command.code().trim();
            if (normalizedCode.length() > 50) {
                throw new InvalidConsultationDataException("code", "El código del diagnóstico no debe exceder los 50 caracteres.");
            }
        }

        String normalizedNotes = null;
        if (command.notes() != null && !command.notes().isBlank()) {
            normalizedNotes = command.notes().trim();
            if (normalizedNotes.length() > 2000) {
                throw new InvalidConsultationDataException("notes", "Las notas del diagnóstico no deben exceder los 2000 caracteres.");
            }
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        ConsultationSummary consultation = consultationRepository.lockConsultationForUpdate(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        if (!consultation.veterinarianId().equals(actorUserId)) {
            throw new ConsultationAccessDeniedException(
                    "Solo el veterinario asignado a la consulta puede registrar o modificar diagnósticos.");
        }

        if (!"BORRADOR".equals(consultation.status())) {
            throw new InvalidConsultationStatusException(consultation.status(), "BORRADOR");
        }

        Instant now = clock.instant();

        DiagnosisSummary updated = consultationRepository.updateDiagnosis(new UpdateDiagnosisRecord(
                tenantId,
                consultationId,
                diagnosisId,
                normalizedCode,
                normalizedDescription,
                normalizedType,
                normalizedNotes,
                now));

        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                consultation.branchId(),
                "CONSULTATION",
                consultationId,
                "DIAGNOSIS_UPDATED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "consultationId", consultationId.toString(),
                        "diagnosisId", diagnosisId.toString(),
                        "diagnosisType", normalizedType,
                        "hasCode", String.valueOf(normalizedCode != null),
                        "patientId", consultation.patientId().toString(),
                        "branchId", consultation.branchId().toString())));

        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientDiagnosisSummary> listPatientDiagnoses(UUID patientId) {
        if (patientId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        if (!consultationRepository.patientExists(metadata.tenantId(), patientId)) {
            throw new ConsultationResourceNotFoundException();
        }
        return consultationRepository.findDiagnosesByPatientId(metadata.tenantId(), patientId);
    }
}
