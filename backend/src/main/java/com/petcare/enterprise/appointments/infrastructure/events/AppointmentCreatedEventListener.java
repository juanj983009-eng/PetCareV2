package com.petcare.enterprise.appointments.infrastructure.events;

import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processes {@link AppointmentCreated} exclusively AFTER_COMMIT within the MVP modular monolith.
 * Note: This is an internal in-memory non-durable event listener (no outbox or external broker).
 */
@Component
public class AppointmentCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentCreatedEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCreated(AppointmentCreated event) {
        log.info(
                "Processed AppointmentCreated AFTER_COMMIT [appointmentId={}, tenantId={}, branchId={}, veterinarianId={}, status={}, correlationId={}]",
                event.appointmentId(),
                event.tenantId(),
                event.branchId(),
                event.veterinarianId(),
                event.status(),
                event.correlationId());
    }
}
