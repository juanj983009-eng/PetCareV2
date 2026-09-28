package com.petcare.enterprise.appointments.infrastructure.events;

import com.petcare.enterprise.appointments.domain.AppointmentCancelled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processes {@link AppointmentCancelled} exclusively AFTER_COMMIT within the MVP modular monolith.
 * Free-text cancellation reason is intentionally never logged.
 */
@Component
public class AppointmentCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentCancelledEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAppointmentCancelled(AppointmentCancelled event) {
        log.info(
                "Processed AppointmentCancelled AFTER_COMMIT [appointmentId={}, tenantId={}, branchId={}, previousStatus={}, newStatus={}, correlationId={}]",
                event.appointmentId(),
                event.tenantId(),
                event.branchId(),
                event.previousStatus(),
                event.newStatus(),
                event.correlationId());
    }
}
