package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase.NewPatient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

record CreatePatientRequest(@NotNull UUID ownerId, @NotBlank @Size(max=100) String name, @NotBlank @Size(max=50) String species,
                            @Size(max=100) String breed, @NotBlank @Size(max=20) String sex, @PastOrPresent LocalDate birthDate,
                            @Size(max=80) String color, @Size(max=80) String microchip, Boolean sterilized) {
    NewPatient toCommand() { return new NewPatient(ownerId,name,species,breed,sex,birthDate,color,microchip,sterilized); }
}
