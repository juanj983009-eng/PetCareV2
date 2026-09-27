package com.petcare.enterprise.appointments.application;

public final class InvalidAppointmentScheduleQueryException extends RuntimeException {

    private final String field;
    private final String reason;

    public InvalidAppointmentScheduleQueryException(String field, String reason) {
        super(reason);
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
