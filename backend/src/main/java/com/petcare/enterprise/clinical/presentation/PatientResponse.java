package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientRegistrationUseCase.Patient;
import java.time.LocalDate;
import java.util.UUID;

record PatientResponse(UUID id, UUID ownerId, String name, String species, String breed, String sex, LocalDate birthDate, String color, String microchip, Boolean sterilized) {
    static PatientResponse from(Patient p) { return new PatientResponse(p.id(),p.ownerId(),p.name(),p.species(),p.breed(),p.sex(),p.birthDate(),p.color(),p.microchip(),p.sterilized()); }
}
