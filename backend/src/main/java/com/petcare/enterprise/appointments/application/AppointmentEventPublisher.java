package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.appointments.domain.AppointmentCancelled;
import com.petcare.enterprise.appointments.domain.AppointmentCreated;
import com.petcare.enterprise.appointments.domain.AppointmentRescheduled;

public interface AppointmentEventPublisher {

    void publish(AppointmentCreated event);

    void publish(AppointmentRescheduled event);

    void publish(AppointmentCancelled event);
}
