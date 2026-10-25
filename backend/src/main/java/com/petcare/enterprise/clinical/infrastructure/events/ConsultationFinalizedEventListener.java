package com.petcare.enterprise.clinical.infrastructure.events;

import com.petcare.enterprise.clinical.domain.ConsultationFinalized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Processes {@link ConsultationFinalized} exclusively AFTER_COMMIT within the MVP modular monolith.
 * Internal non-durable in-memory event listener.
 */
@Component
public class ConsultationFinalizedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ConsultationFinalizedEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConsultationFinalized(ConsultationFinalized event) {
        log.info(
                "Processed ConsultationFinalized AFTER_COMMIT [consultationId={}, appointmentId={}, tenantId={}, branchId={}, veterinarianId={}, correlationId={}]",
                event.consultationId(),
                event.appointmentId(),
                event.tenantId(),
                event.branchId(),
                event.veterinarianId(),
                event.correlationId());
    }
}
