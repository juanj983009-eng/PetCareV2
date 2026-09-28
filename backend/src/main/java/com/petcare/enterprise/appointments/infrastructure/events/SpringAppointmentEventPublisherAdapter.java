package com.petcare.enterprise.appointments.infrastructure.events;

import com.petcare.enterprise.appointments.application.AppointmentEventPublisher;
import com.petcare.enterprise.appointments.domain.AppointmentCancelled;
import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import com.petcare.enterprise.appointments.domain.AppointmentRescheduled;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringAppointmentEventPublisherAdapter implements AppointmentEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SpringAppointmentEventPublisherAdapter(ApplicationEventPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
    }

    @Override
    public void publish(AppointmentCreated event) {
        Objects.requireNonNull(event, "event is required");
        delegate.publishEvent(event);
    }

    @Override
    public void publish(AppointmentRescheduled event) {
        Objects.requireNonNull(event, "event is required");
        delegate.publishEvent(event);
    }

    @Override
    public void publish(AppointmentCancelled event) {
        Objects.requireNonNull(event, "event is required");
        delegate.publishEvent(event);
    }
}
