package com.petcare.enterprise.appointments.application;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentPage;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.BranchOption;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.NormalizedAppointmentQuery;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.VeterinarianOption;
import java.util.List;
import java.util.UUID;

public interface AppointmentScheduleRepository {

    AppointmentPage search(UUID tenantId, NormalizedAppointmentQuery query);

    List<BranchOption> findActiveBranches(UUID tenantId);

    List<VeterinarianOption> findActiveVeterinarians(UUID tenantId);
}
