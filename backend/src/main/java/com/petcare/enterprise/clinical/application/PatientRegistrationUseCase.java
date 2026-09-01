package com.petcare.enterprise.clinical.application;

import java.time.LocalDate;
import java.util.UUID;

public interface PatientRegistrationUseCase {
    Patient register(NewPatient patient);

    record NewPatient(UUID ownerId, String name, String species, String breed, String sex, LocalDate birthDate,
                      String color, String microchip, Boolean sterilized) { }
    record Patient(UUID id, UUID ownerId, String name, String species, String breed, String sex, LocalDate birthDate,
                   String color, String microchip, Boolean sterilized) { }
}
