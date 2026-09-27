package com.petcare.enterprise.appointments.presentation;

import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentFilterOptions;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.BranchOption;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.VeterinarianOption;
import java.util.List;
import java.util.UUID;

record AppointmentOptionsResponse(
        List<BranchItemResponse> branches,
        List<VeterinarianItemResponse> veterinarians) {

    static AppointmentOptionsResponse from(AppointmentFilterOptions options) {
        return new AppointmentOptionsResponse(
                options.branches().stream().map(BranchItemResponse::from).toList(),
                options.veterinarians().stream().map(VeterinarianItemResponse::from).toList());
    }

    record BranchItemResponse(
            UUID id,
            String name,
            String timeZone) {

        static BranchItemResponse from(BranchOption branch) {
            return new BranchItemResponse(
                    branch.id(),
                    branch.name(),
                    branch.timeZone());
        }
    }

    record VeterinarianItemResponse(
            UUID id,
            String fullName) {

        static VeterinarianItemResponse from(VeterinarianOption vet) {
            return new VeterinarianItemResponse(
                    vet.id(),
                    vet.fullName());
        }
    }
}
