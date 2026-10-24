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
import com.petcare.enterprise.clinical.domain.ConsultationStatus;
import java.time.Clock;
import java.time.Instant;
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
}
