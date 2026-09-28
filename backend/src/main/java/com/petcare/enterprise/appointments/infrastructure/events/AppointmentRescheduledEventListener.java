package com.petcare.enterprise.appointments.infrastructure.events;

import com.petcare.enterprise.appointments.domain.AppointmentRescheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processes {@link AppointmentRescheduled} exclusively AFTER_COMMIT within the MVP modular monolith.
 * Note: This is an internal in-memory non-durable event listener (no outbox or external broker).
 */
@Component
public class AppointmentRescheduledEventListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentRescheduledEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentRescheduled(AppointmentRescheduled event) {
        log.info(
                "Processed AppointmentRescheduled AFTER_COMMIT [appointmentId={}, tenantId={}, previousBranchId={}, newBranchId={}, previousVeterinarianId={}, newVeterinarianId={}, previousStatus={}, newStatus={}, correlationId={}]",
                event.appointmentId(),
                event.tenantId(),
                event.previousBranchId(),
                event.newBranchId(),
                event.previousVeterinarianId(),
                event.newVeterinarianId(),
                event.previousStatus(),
                event.newStatus(),
                event.correlationId());
    }
}
