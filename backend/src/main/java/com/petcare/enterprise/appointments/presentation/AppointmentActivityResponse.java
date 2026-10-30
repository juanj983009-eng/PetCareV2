package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentActivity;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentActivityDay;
import java.time.LocalDate;
import java.util.List;

record AppointmentActivityResponse(
        String timeZone,
        LocalDate startDate,
        LocalDate endDate,
        List<AppointmentActivityDayResponse> items) {

    static AppointmentActivityResponse from(AppointmentActivity activity) {
        return new AppointmentActivityResponse(
                activity.timeZone(),
                activity.startDate(),
                activity.endDate(),
                activity.items().stream()
                        .map(AppointmentActivityDayResponse::from)
                        .toList());
    }

    record AppointmentActivityDayResponse(
            LocalDate date,
            long completedCount,
            long otherCount,
            long totalCount) {

        static AppointmentActivityDayResponse from(AppointmentActivityDay item) {
            return new AppointmentActivityDayResponse(
                    item.date(),
                    item.completedCount(),
                    item.otherCount(),
                    item.totalCount());
        }
    }
}
