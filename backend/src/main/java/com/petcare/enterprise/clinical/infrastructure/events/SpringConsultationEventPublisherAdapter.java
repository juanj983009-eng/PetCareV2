package com.petcare.enterprise.clinical.infrastructure.events;

import com.petcare.enterprise.clinical.application.ConsultationEventPublisher;
import com.petcare.enterprise.clinical.domain.ConsultationFinalized;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringConsultationEventPublisherAdapter implements ConsultationEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SpringConsultationEventPublisherAdapter(ApplicationEventPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
    }

    @Override
    public void publish(ConsultationFinalized event) {
        Objects.requireNonNull(event, "event is required");
        delegate.publishEvent(event);
    }
}
