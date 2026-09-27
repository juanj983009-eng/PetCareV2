package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.appointments.domain.AppointmentCreated;

public interface AppointmentEventPublisher {

    void publish(AppointmentCreated event);
}
