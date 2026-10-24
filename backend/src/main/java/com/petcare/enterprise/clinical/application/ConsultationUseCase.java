package com.petcare.enterprise.clinical.application;

import java.time.Instant;
import java.util.UUID;

public interface ConsultationUseCase {

    StartConsultationResult startFromAppointment(UUID appointmentId);

    record StartConsultationResult(
            ConsultationSummary consultation,
            boolean created) {
    }

    record ConsultationSummary(
            UUID id,
            UUID appointmentId,
            String appointmentStatus,
            UUID patientId,
            String patientName,
            UUID medicalRecordId,
            UUID branchId,
            String branchName,
            UUID veterinarianId,
            String veterinarianFullName,
            String status,
            Instant startedAt,
            Instant createdAt,
            Instant updatedAt) {
    }
}
