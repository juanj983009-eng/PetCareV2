package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository.AppointmentStatusHistoryRecord;
import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository.NewAppointmentRecord;
import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository.VeterinarianEligibility;
import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import com.petcare.enterprise.appointments.domain.AppointmentStatus;
import com.petcare.enterprise.appointments.domain.AppointmentType;
import com.petcare.enterprise.audit.application.AuditLogWriter;
import com.petcare.enterprise.audit.application.AuditMetadata;
import com.petcare.enterprise.audit.application.AuditMetadataProvider;
import com.petcare.enterprise.audit.application.AuditRecord;
import com.petcare.enterprise.tenancy.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentScheduleService implements AppointmentScheduleUseCase {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_NOTES_LENGTH = 1000;

    private final TenantContext tenantContext;
    private final AppointmentScheduleRepository repository;
    private final AuditMetadataProvider audit;
    private final AuditLogWriter auditWriter;
    private final AppointmentEventPublisher eventPublisher;
    private final Clock clock;

    public AppointmentScheduleService(
            TenantContext tenantContext,
            AppointmentScheduleRepository repository,
            AuditMetadataProvider audit,
            AuditLogWriter auditWriter,
            AppointmentEventPublisher eventPublisher,
            Clock clock) {
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentPage listSchedule(AppointmentScheduleQuery query) {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        NormalizedAppointmentQuery normalized = normalize(query);
        return repository.search(tenantId, normalized);
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentFilterOptions listOptions() {
        return listOptions(null);
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentFilterOptions listOptions(String branchId) {
        UUID tenantId = tenantContext.requireTenant().tenantId();
        UUID filterBranchId = parseOptionalUuid(
                branchId,
                "branchId",
                "El identificador de sucursal no es un UUID válido.");
        List<BranchOption> branches = repository.findActiveBranches(tenantId);
        List<VeterinarianOption> veterinarians = repository.findActiveVeterinarians(tenantId);
        if (filterBranchId != null) {
            veterinarians = veterinarians.stream()
                    .filter(vet -> vet.branchIds() != null && vet.branchIds().contains(filterBranchId))
                    .toList();
        }
        return new AppointmentFilterOptions(branches, veterinarians);
    }

    @Override
    @Transactional
    public AppointmentSummary createAppointment(CreateAppointmentCommand command) {
        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();

        if (command == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "appointment",
                    "Los datos de la cita son obligatorios.");
        }
        if (command.patientId() == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "patientId",
                    "El paciente es obligatorio.");
        }
        if (command.branchId() == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "branchId",
                    "La sucursal es obligatoria.");
        }
        if (command.veterinarianId() == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "veterinarianId",
                    "El veterinario es obligatorio.");
        }

        AppointmentType parsedType = AppointmentType.parse(command.appointmentType())
                .orElseThrow(() -> new InvalidAppointmentScheduleQueryException(
                        "appointmentType",
                        "El tipo de cita no pertenece al catálogo permitido."));

        LocalDateTime startsAtLocal = parseLocalDateTime(
                command.startsAtLocal(),
                "startsAtLocal",
                "La fecha y hora de inicio es obligatoria en formato ISO local (YYYY-MM-DDTHH:mm).");
        LocalDateTime endsAtLocal = parseLocalDateTime(
                command.endsAtLocal(),
                "endsAtLocal",
                "La fecha y hora de fin es obligatoria en formato ISO local (YYYY-MM-DDTHH:mm).");

        if (!endsAtLocal.isAfter(startsAtLocal)) {
            throw new InvalidAppointmentScheduleQueryException(
                    "endsAtLocal",
                    "La fecha y hora de fin debe ser posterior a la fecha y hora de inicio.");
        }

        String normalizedReason = normalizeOptionalText(
                command.reason(),
                MAX_REASON_LENGTH,
                "reason",
                "El motivo no puede superar los " + MAX_REASON_LENGTH + " caracteres.");
        String normalizedNotes = normalizeOptionalText(
                command.notes(),
                MAX_NOTES_LENGTH,
                "notes",
                "Las observaciones no pueden superar los " + MAX_NOTES_LENGTH + " caracteres.");

        BranchOption branch = repository.findActiveBranchById(tenantId, command.branchId())
                .orElseThrow(AppointmentResourceNotFoundException::new);

        ZoneId branchZone;
        try {
            branchZone = ZoneId.of(branch.timeZone());
        } catch (Exception exception) {
            throw new InvalidAppointmentScheduleQueryException(
                    "branchId",
                    "La zona horaria configurada en la sucursal no es válida.");
        }

        Instant startsAt = resolveInstantInBranchZone(startsAtLocal, branchZone, "startsAtLocal");
        Instant endsAt = resolveInstantInBranchZone(endsAtLocal, branchZone, "endsAtLocal");

        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidAppointmentScheduleQueryException(
                    "endsAtLocal",
                    "La fecha y hora de fin debe ser posterior a la fecha y hora de inicio.");
        }

        String patientName = repository.findActivePatientName(tenantId, command.patientId())
                .orElseThrow(AppointmentResourceNotFoundException::new);

        VeterinarianEligibility veterinarian = repository.findActiveUserEligibility(
                        tenantId,
                        command.veterinarianId(),
                        command.branchId())
                .orElseThrow(AppointmentResourceNotFoundException::new);

        if (!veterinarian.hasVeterinarianRole()) {
            throw new InvalidAppointmentScheduleQueryException(
                    "veterinarianId",
                    "El usuario seleccionado no cuenta con el rol VETERINARIO activo en la clínica.");
        }
        if (!veterinarian.assignedToBranch()) {
            throw new InvalidAppointmentScheduleQueryException(
                    "veterinarianId",
                    "El veterinario seleccionado no se encuentra asignado a la sucursal indicada.");
        }

        if (repository.hasOverlappingActiveAppointment(tenantId, command.veterinarianId(), startsAt, endsAt)) {
            throw new AppointmentTimeConflictException();
        }

        Instant now = clock.instant();
        UUID appointmentId = UUID.randomUUID();
        String status = AppointmentStatus.PROGRAMADA.name();

        AppointmentSummary created;
        try {
            created = repository.insertAppointment(new NewAppointmentRecord(
                    appointmentId,
                    tenantId,
                    branch.id(),
                    branch.name(),
                    command.patientId(),
                    patientName,
                    veterinarian.id(),
                    veterinarian.fullName(),
                    startsAt,
                    endsAt,
                    parsedType.name(),
                    status,
                    normalizedReason,
                    normalizedNotes,
                    metadata.actorUserId(),
                    now));
        } catch (DataIntegrityViolationException exception) {
            throw new AppointmentTimeConflictException();
        }

        auditWriter.append(new AuditRecord(
                UUID.randomUUID(),
                tenantId,
                metadata.actorUserId(),
                created.branchId(),
                "APPOINTMENT",
                created.id(),
                "APPOINTMENT_CREATED",
                now,
                null,
                metadata.correlationId(),
                null,
                Map.of(
                        "appointmentId", created.id().toString(),
                        "branchId", created.branchId().toString(),
                        "patientId", created.patientId().toString(),
                        "veterinarianId", created.veterinarianId().toString(),
                        "createdBy", metadata.actorUserId().toString(),
                        "startsAt", created.startsAt().toString(),
                        "endsAt", created.endsAt().toString(),
                        "appointmentType", created.appointmentType(),
                        "status", created.status())));

        eventPublisher.publish(new AppointmentCreated(
                tenantId,
                created.id(),
                created.branchId(),
                created.patientId(),
                created.veterinarianId(),
                metadata.actorUserId(),
                created.startsAt(),
                created.endsAt(),
                created.appointmentType(),
                created.status(),
                metadata.correlationId()));

        return created;
    }

    @Override
    @Transactional
    public AppointmentSummary updateStatus(UpdateAppointmentStatusCommand command) {
        AuditMetadata metadata = audit.requireTenantMetadata();
        UUID tenantId = metadata.tenantId();

        if (command == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "status",
                    "Los datos del cambio de estado son obligatorios.");
        }
        if (command.appointmentId() == null) {
            throw new InvalidAppointmentScheduleQueryException(
                    "appointmentId",
                    "El identificador de la cita es obligatorio.");
        }
        if (command.status() == null || command.status().isBlank()) {
            throw new InvalidAppointmentScheduleQueryException(
                    "status",
                    "El estado de destino es obligatorio.");
        }

        String normalizedTarget = command.status().trim().toUpperCase(Locale.ROOT);

        for (int attempt = 0; attempt < 3; attempt++) {
            AppointmentSummary current = repository.findById(tenantId, command.appointmentId())
                    .orElseThrow(AppointmentResourceNotFoundException::new);

            AppointmentStatus targetStatus = parseAllowedTargetStatus(current.status(), normalizedTarget);
            AppointmentStatus currentStatus = AppointmentStatus.valueOf(current.status());

            if (currentStatus == targetStatus) {
                return current;
            }

            if (!isAllowedTransition(currentStatus, targetStatus)) {
                throw new InvalidAppointmentStatusTransitionException(currentStatus.name(), targetStatus.name());
            }

            Instant now = clock.instant();
            boolean updated = repository.updateStatusConditionally(
                    tenantId,
                    command.appointmentId(),
                    currentStatus.name(),
                    targetStatus.name(),
                    now);

            if (updated) {
                AppointmentSummary updatedSummary = new AppointmentSummary(
                        current.id(),
                        current.startsAt(),
                        current.endsAt(),
                        current.appointmentType(),
                        targetStatus.name(),
                        current.patientId(),
                        current.patientName(),
                        current.branchId(),
                        current.branchName(),
                        current.veterinarianId(),
                        current.veterinarianFullName());

                repository.insertStatusHistory(new AppointmentStatusHistoryRecord(
                        UUID.randomUUID(),
                        tenantId,
                        updatedSummary.id(),
                        currentStatus.name(),
                        targetStatus.name(),
                        metadata.actorUserId(),
                        now,
                        metadata.correlationId()));

                auditWriter.append(new AuditRecord(
                        UUID.randomUUID(),
                        tenantId,
                        metadata.actorUserId(),
                        updatedSummary.branchId(),
                        "APPOINTMENT",
                        updatedSummary.id(),
                        "APPOINTMENT_STATUS_CHANGED",
                        now,
                        null,
                        metadata.correlationId(),
                        Map.of("status", currentStatus.name()),
                        Map.of(
                                "appointmentId", updatedSummary.id().toString(),
                                "branchId", updatedSummary.branchId().toString(),
                                "previousStatus", currentStatus.name(),
                                "newStatus", targetStatus.name())));

                return updatedSummary;
            }
        }

        throw new InvalidAppointmentStatusTransitionException();
    }

    private static AppointmentStatus parseAllowedTargetStatus(String currentStatus, String normalizedTarget) {
        if ("CONFIRMADA".equals(normalizedTarget)) {
            return AppointmentStatus.CONFIRMADA;
        }
        if ("EN_ESPERA".equals(normalizedTarget)) {
            return AppointmentStatus.EN_ESPERA;
        }
        throw new InvalidAppointmentStatusTransitionException(currentStatus, normalizedTarget);
    }

    private static boolean isAllowedTransition(AppointmentStatus currentStatus, AppointmentStatus targetStatus) {
        if (currentStatus == AppointmentStatus.PROGRAMADA) {
            return targetStatus == AppointmentStatus.CONFIRMADA
                    || targetStatus == AppointmentStatus.EN_ESPERA;
        }
        if (currentStatus == AppointmentStatus.CONFIRMADA) {
            return targetStatus == AppointmentStatus.EN_ESPERA;
        }
        return false;
    }

    private static Instant resolveInstantInBranchZone(
            LocalDateTime localDateTime,
            ZoneId zoneId,
            String fieldName) {
        List<ZoneOffset> validOffsets = zoneId.getRules().getValidOffsets(localDateTime);
        if (validOffsets.isEmpty()) {
            throw new InvalidAppointmentScheduleQueryException(
                    fieldName,
                    "La hora local indicada no existe en la zona horaria de la sucursal por cambio de horario (DST).");
        }
        if (validOffsets.size() > 1) {
            throw new InvalidAppointmentScheduleQueryException(
                    fieldName,
                    "La hora local indicada es ambigua en la zona horaria de la sucursal por cambio de horario (DST).");
        }
        return ZonedDateTime.ofStrict(localDateTime, validOffsets.get(0), zoneId).toInstant();
    }

    private static LocalDateTime parseLocalDateTime(String value, String field, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new InvalidAppointmentScheduleQueryException(field, errorMessage);
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("Z") || trimmed.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            throw new InvalidAppointmentScheduleQueryException(
                    field,
                    "Debe enviarse hora local sin desplazamiento UTC explícito.");
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException exception) {
            throw new InvalidAppointmentScheduleQueryException(field, errorMessage);
        }
    }

    private static String normalizeOptionalText(String value, int maxLength, String field, String message) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new InvalidAppointmentScheduleQueryException(field, message);
        }
        return trimmed;
    }

    private static NormalizedAppointmentQuery normalize(AppointmentScheduleQuery query) {
        if (query == null || query.date() == null || query.date().isBlank()) {
            throw new InvalidAppointmentScheduleQueryException(
                    "date",
                    "La fecha de consulta es obligatoria en formato YYYY-MM-DD.");
        }
        String trimmedDate = query.date().trim();
        if (!trimmedDate.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            throw new InvalidAppointmentScheduleQueryException(
                    "date",
                    "La fecha debe tener el formato YYYY-MM-DD.");
        }
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(trimmedDate);
        } catch (DateTimeParseException exception) {
            throw new InvalidAppointmentScheduleQueryException(
                    "date",
                    "La fecha proporcionada no es una fecha calendario válida.");
        }

        UUID branchId = parseOptionalUuid(
                query.branchId(),
                "branchId",
                "El identificador de sucursal no es un UUID válido.");
        UUID veterinarianId = parseOptionalUuid(
                query.veterinarianId(),
                "veterinarianId",
                "El identificador de veterinario no es un UUID válido.");

        if (query.page() < 0) {
            throw new InvalidAppointmentScheduleQueryException(
                    "page",
                    "El número de página no puede ser negativo.");
        }
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw new InvalidAppointmentScheduleQueryException(
                    "size",
                    "El tamaño de página debe estar entre 1 y " + MAX_PAGE_SIZE + ".");
        }

        String normalizedDirection = (query.direction() == null || query.direction().isBlank())
                ? "asc"
                : query.direction().trim().toLowerCase(Locale.ROOT);
        if (!"asc".equals(normalizedDirection) && !"desc".equals(normalizedDirection)) {
            throw new InvalidAppointmentScheduleQueryException(
                    "direction",
                    "La dirección de ordenamiento no está permitida.");
        }

        return new NormalizedAppointmentQuery(
                parsedDate,
                branchId,
                veterinarianId,
                query.page(),
                query.size(),
                "asc".equals(normalizedDirection));
    }

    private static UUID parseOptionalUuid(String value, String field, String errorMessage) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAppointmentScheduleQueryException(field, errorMessage);
        }
    }
}
