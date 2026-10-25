package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway;
import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway.AppointmentConsultationContext;
import com.petcare.enterprise.appointments.application.AppointmentConsultationGateway.FinalizeAppointmentAttentionCommand;
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
import com.petcare.enterprise.clinical.application.ConsultationRepository.NewPrescriptionItemRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.NewPrescriptionRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.UpdateDiagnosisRecord;
import com.petcare.enterprise.clinical.application.ConsultationRepository.UpdateEvaluationRecord;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.AddDiagnosisCommand;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.DiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.IssuePrescriptionCommand;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientConsultationPage;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PatientDiagnosisSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionItemCommand;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.PrescriptionSummary;
import com.petcare.enterprise.clinical.application.ConsultationUseCase.UpdateDiagnosisCommand;
import com.petcare.enterprise.clinical.domain.ClinicalDetails;
import com.petcare.enterprise.clinical.domain.ConsultationFinalized;
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
    private final ConsultationEventPublisher eventPublisher;
    private final Clock clock;

    public ConsultationService(
            AppointmentConsultationGateway appointmentGateway,
            ConsultationRepository consultationRepository,
            AuditMetadataProvider audit,
            AuditLogWriter auditWriter,
            ConsultationEventPublisher eventPublisher,
            Clock clock) {
        this.appointmentGateway = Objects.requireNonNull(appointmentGateway, "appointmentGateway is required");
        this.consultationRepository = Objects.requireNonNull(consultationRepository, "consultationRepository is required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
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
    @Transactional(readOnly = true)
    public ConsultationSummary getByAppointmentId(UUID appointmentId) {
        if (appointmentId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        return consultationRepository.findByAppointmentId(metadata.tenantId(), appointmentId)
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
    @Transactional
    public ConsultationSummary finalizeConsultation(UUID consultationId) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        // 1. Lock consultation row to serialize with other clinical writes and prevent deadlocks
        ConsultationSummary consultation = consultationRepository.lockConsultationForUpdate(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        // 2. Validate actor authorization (only assigned veterinarian can finalize)
        if (!consultation.veterinarianId().equals(actorUserId)) {
            throw new ConsultationAccessDeniedException("Solo el veterinario asignado a la consulta puede finalizarla.");
        }

        // 3. Idempotent check: if already finalized, return existing without duplicating audit or events
        if (ConsultationStatus.FINALIZADA.name().equals(consultation.status())) {
            return consultation;
        }

        // 4. Validate valid status: must be BORRADOR
        if (!ConsultationStatus.BORRADOR.name().equals(consultation.status())) {
            throw new InvalidConsultationStatusException("La consulta no se encuentra en estado borrador.");
        }

        // 5. Clinical minimum validation: reason must be present and not blank
        if (consultation.reason() == null || consultation.reason().isBlank()) {
            throw new InvalidConsultationDataException("reason", "El motivo de la consulta debe estar registrado para poder finalizarla.");
        }

        Instant now = clock.instant();

        // 6. Transition appointment from EN_ATENCION to FINALIZADA via public gateway
        boolean appointmentFinalized = appointmentGateway.finalizeAttention(new FinalizeAppointmentAttentionCommand(
                tenantId,
                consultation.appointmentId(),
                consultation.branchId(),
                actorUserId,
                now,
                metadata.correlationId()));

        if (!appointmentFinalized) {
            AppointmentConsultationContext reloaded = appointmentGateway.findForConsultation(tenantId, consultation.appointmentId())
                    .orElseThrow(ConsultationResourceNotFoundException::new);
            throw new InvalidAppointmentStatusTransitionException(reloaded.status(), "FINALIZADA");
        }

        // 7. Transition consultation status to FINALIZADA
        ConsultationSummary finalized = consultationRepository.finalizeConsultation(tenantId, consultationId, now);

        // 8. Transactional audit log
        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                finalized.branchId(),
                "CONSULTATION",
                finalized.id(),
                "CONSULTATION_FINALIZED",
                now,
                null,
                metadata.correlationId(),
                Map.of("status", ConsultationStatus.BORRADOR.name()),
                Map.of(
                        "consultationId", finalized.id().toString(),
                        "appointmentId", finalized.appointmentId().toString(),
                        "patientId", finalized.patientId().toString(),
                        "branchId", finalized.branchId().toString(),
                        "veterinarianId", finalized.veterinarianId().toString(),
                        "previousStatus", ConsultationStatus.BORRADOR.name(),
                        "newStatus", ConsultationStatus.FINALIZADA.name(),
                        "finalizedAt", now.toString())));

        // 9. Publish domain event ConsultationFinalized (processed AFTER_COMMIT)
        eventPublisher.publish(new ConsultationFinalized(
                tenantId,
                finalized.id(),
                finalized.appointmentId(),
                finalized.patientId(),
                finalized.branchId(),
                finalized.veterinarianId(),
                now,
                metadata.correlationId()));

        return finalized;
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

    @Override
    @Transactional(readOnly = true)
    public PatientConsultationPage listPatientConsultations(UUID patientId, int page, int size) {
        if (patientId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        if (!consultationRepository.patientExists(metadata.tenantId(), patientId)) {
            throw new ConsultationResourceNotFoundException();
        }
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        return consultationRepository.findConsultationsByPatientId(
                metadata.tenantId(), patientId, normalizedPage, normalizedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionSummary> listPrescriptions(UUID consultationId) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        AuditMetadata metadata = audit.requireTenantMetadata();
        consultationRepository.findById(metadata.tenantId(), consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);
        return consultationRepository.findPrescriptionsByConsultationId(metadata.tenantId(), consultationId);
    }

    @Override
    @Transactional
    public PrescriptionSummary issuePrescription(UUID consultationId, IssuePrescriptionCommand command) {
        if (consultationId == null) {
            throw new ConsultationResourceNotFoundException();
        }
        if (command == null) {
            throw new InvalidConsultationDataException("command", "Los datos de la prescripción son obligatorios.");
        }

        // Explicit rejection of stock deduction or product catalog attempts
        if (Boolean.TRUE.equals(command.deductStock()) || command.productId() != null) {
            throw new InvalidConsultationDataException(
                    "deductStock",
                    "La dispensación y descuento de inventario no están disponibles en este momento. Solo se admiten medicamentos externos al catálogo.");
        }

        if (command.items() == null || command.items().isEmpty()) {
            throw new InvalidConsultationDataException("items", "La prescripción debe incluir al menos un medicamento.");
        }

        List<PrescriptionItemCommand> rawItems = command.items();
        for (int i = 0; i < rawItems.size(); i++) {
            PrescriptionItemCommand item = rawItems.get(i);
            if (item == null) {
                throw new InvalidConsultationDataException("items[" + i + "]", "La línea de prescripción es obligatoria.");
            }
            if (Boolean.TRUE.equals(item.deductStock()) || item.productId() != null) {
                throw new InvalidConsultationDataException(
                        "items[" + i + "].deductStock",
                        "La dispensación y descuento de inventario no están disponibles en este momento. Solo se admiten medicamentos externos al catálogo.");
            }
            if (item.medicationName() == null || item.medicationName().isBlank()) {
                throw new InvalidConsultationDataException("items[" + i + "].medicationName", "El nombre del medicamento es obligatorio.");
            }
            if (item.medicationName().trim().length() > 200) {
                throw new InvalidConsultationDataException("items[" + i + "].medicationName", "El nombre del medicamento no debe exceder los 200 caracteres.");
            }
            if (item.dosage() == null || item.dosage().isBlank()) {
                throw new InvalidConsultationDataException("items[" + i + "].dosage", "La dosis del medicamento es obligatoria.");
            }
            if (item.dosage().trim().length() > 100) {
                throw new InvalidConsultationDataException("items[" + i + "].dosage", "La dosis no debe exceder los 100 caracteres.");
            }
            if (item.frequency() == null || item.frequency().isBlank()) {
                throw new InvalidConsultationDataException("items[" + i + "].frequency", "La frecuencia del medicamento es obligatoria.");
            }
            if (item.frequency().trim().length() > 100) {
                throw new InvalidConsultationDataException("items[" + i + "].frequency", "La frecuencia no debe exceder los 100 caracteres.");
            }
            if (item.duration() == null || item.duration().isBlank()) {
                throw new InvalidConsultationDataException("items[" + i + "].duration", "La duración del tratamiento es obligatoria.");
            }
            if (item.duration().trim().length() > 100) {
                throw new InvalidConsultationDataException("items[" + i + "].duration", "La duración no debe exceder los 100 caracteres.");
            }
            if (item.route() != null && !item.route().isBlank() && item.route().trim().length() > 50) {
                throw new InvalidConsultationDataException("items[" + i + "].route", "La vía de administración no debe exceder los 50 caracteres.");
            }
            if (item.instructions() != null && !item.instructions().isBlank() && item.instructions().trim().length() > 2000) {
                throw new InvalidConsultationDataException("items[" + i + "].instructions", "Las indicaciones del medicamento no deben exceder los 2000 caracteres.");
            }
        }

        String normalizedGeneralInstructions = null;
        if (command.generalInstructions() != null && !command.generalInstructions().isBlank()) {
            normalizedGeneralInstructions = command.generalInstructions().trim();
            if (normalizedGeneralInstructions.length() > 2000) {
                throw new InvalidConsultationDataException("generalInstructions", "Las indicaciones generales no deben exceder los 2000 caracteres.");
            }
        }

        String normalizedIdempotencyKey = null;
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            normalizedIdempotencyKey = command.idempotencyKey().trim();
            if (normalizedIdempotencyKey.length() > 100) {
                throw new InvalidConsultationDataException(
                        "idempotencyKey",
                        "La clave de idempotencia no debe exceder los 100 caracteres.");
            }
        }

        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();
        UUID actorUserId = metadata.actorUserId();

        // Lock consultation tenant-aware
        ConsultationSummary consultation = consultationRepository.lockConsultationForUpdate(tenantId, consultationId)
                .orElseThrow(ConsultationResourceNotFoundException::new);

        if (!consultation.veterinarianId().equals(actorUserId)) {
            throw new ConsultationAccessDeniedException(
                    "Solo el veterinario asignado a la consulta puede emitir prescripciones.");
        }

        // Check idempotency under lock to handle concurrent retry attempts
        if (normalizedIdempotencyKey != null) {
            Optional<PrescriptionSummary> existingUnderLock = consultationRepository.findPrescriptionByIdempotencyKey(
                    tenantId, consultationId, normalizedIdempotencyKey);
            if (existingUnderLock.isPresent()) {
                PrescriptionSummary original = existingUnderLock.get();
                if (hasMatchingContent(original, normalizedGeneralInstructions, rawItems)) {
                    return original;
                } else {
                    throw new PrescriptionIdempotencyConflictException(
                            "La clave de idempotencia ya fue utilizada con un contenido diferente para esta consulta.");
                }
            }
        }

        if (!"BORRADOR".equals(consultation.status())) {
            throw new InvalidConsultationStatusException(consultation.status(), "BORRADOR");
        }

        UUID prescriptionId = UUID.randomUUID();
        Instant now = clock.instant();

        List<NewPrescriptionItemRecord> itemRecords = new java.util.ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) {
            PrescriptionItemCommand item = rawItems.get(i);
            String route = item.route() != null && !item.route().isBlank() ? item.route().trim() : null;
            String instructions = item.instructions() != null && !item.instructions().isBlank() ? item.instructions().trim() : null;
            itemRecords.add(new NewPrescriptionItemRecord(
                    UUID.randomUUID(),
                    tenantId,
                    prescriptionId,
                    i + 1,
                    item.medicationName().trim(),
                    item.dosage().trim(),
                    item.frequency().trim(),
                    item.duration().trim(),
                    route,
                    instructions,
                    now));
        }

        NewPrescriptionRecord prescriptionRecord = new NewPrescriptionRecord(
                prescriptionId,
                tenantId,
                consultationId,
                actorUserId,
                consultation.veterinarianFullName(),
                now,
                normalizedGeneralInstructions,
                "EMITIDA",
                normalizedIdempotencyKey,
                now,
                now);

        PrescriptionSummary created = consultationRepository.insertPrescription(prescriptionRecord, itemRecords);

        // Append audit log within the same transaction without leaking sensitive drug dosages/text
        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                actorUserId,
                consultation.branchId(),
                "PRESCRIPTION",
                prescriptionId,
                "PRESCRIPTION_ISSUED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "consultationId", consultationId.toString(),
                        "prescriptionId", prescriptionId.toString(),
                        "itemCount", String.valueOf(itemRecords.size()),
                        "patientId", consultation.patientId().toString(),
                        "branchId", consultation.branchId().toString(),
                        "hasGeneralInstructions", String.valueOf(normalizedGeneralInstructions != null))));

        return created;
    }

    private boolean hasMatchingContent(
            PrescriptionSummary existing,
            String normalizedGeneralInstructions,
            List<PrescriptionItemCommand> rawItems) {
        if (!Objects.equals(existing.generalInstructions(), normalizedGeneralInstructions)) {
            return false;
        }
        List<PrescriptionItemSummary> existingItems = existing.items();
        if (existingItems == null || existingItems.size() != rawItems.size()) {
            return false;
        }
        for (int i = 0; i < rawItems.size(); i++) {
            PrescriptionItemSummary ex = existingItems.get(i);
            PrescriptionItemCommand in = rawItems.get(i);
            if (!Objects.equals(ex.medicationName(), in.medicationName().trim())) {
                return false;
            }
            if (!Objects.equals(ex.dosage(), in.dosage().trim())) {
                return false;
            }
            if (!Objects.equals(ex.frequency(), in.frequency().trim())) {
                return false;
            }
            if (!Objects.equals(ex.duration(), in.duration().trim())) {
                return false;
            }
            String normRoute = in.route() != null && !in.route().isBlank() ? in.route().trim() : null;
            if (!Objects.equals(ex.route(), normRoute)) {
                return false;
            }
            String normInst = in.instructions() != null && !in.instructions().isBlank() ? in.instructions().trim() : null;
            if (!Objects.equals(ex.instructions(), normInst)) {
                return false;
            }
        }
        return true;
    }
}
