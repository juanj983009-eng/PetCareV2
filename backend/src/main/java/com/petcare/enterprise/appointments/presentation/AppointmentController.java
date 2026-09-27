package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentScheduleQuery;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController {

    private final AppointmentScheduleUseCase schedule;

    AppointmentController(AppointmentScheduleUseCase schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule is required");
    }

    @GetMapping
    AppointmentPageResponse listSchedule(
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "branchId", required = false) String branchId,
            @RequestParam(name = "veterinarianId", required = false) String veterinarianId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "direction", defaultValue = "asc") String direction) {
        return AppointmentPageResponse.from(schedule.listSchedule(new AppointmentScheduleQuery(
                date,
                branchId,
                veterinarianId,
                page,
                size,
                direction)));
    }

    @GetMapping("/options")
    AppointmentOptionsResponse listOptions() {
        return AppointmentOptionsResponse.from(schedule.listOptions());
    }
}
