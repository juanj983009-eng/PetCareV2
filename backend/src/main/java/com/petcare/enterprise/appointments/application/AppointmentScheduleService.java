package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.tenancy.application.TenantContext;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentScheduleService implements AppointmentScheduleUseCase {

    private static final int MAX_PAGE_SIZE = 50;

    private final TenantContext tenantContext;
    private final AppointmentScheduleRepository repository;

    public AppointmentScheduleService(
            TenantContext tenantContext,
            AppointmentScheduleRepository repository) {
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
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
        UUID tenantId = tenantContext.requireTenant().tenantId();
        return new AppointmentFilterOptions(
                repository.findActiveBranches(tenantId),
                repository.findActiveVeterinarians(tenantId));
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
