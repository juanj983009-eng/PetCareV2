package com.petcare.enterprise.clinical.application;

import java.util.List;
import java.util.UUID;

public interface PatientSearchUseCase {
    PatientPage search(PatientSearch query);

    record PatientSearch(
            int page,
            int size,
            String query,
            String species,
            String status,
            SortField sortBy,
            SortDirection direction) { }

    enum SortField {
        NAME,
        SPECIES,
        OWNER_NAME,
        STATUS,
        CREATED_AT
    }

    enum SortDirection {
        ASC,
        DESC
    }

    record PatientSummary(
            UUID id,
            String name,
            String species,
            String breed,
            String sex,
            String microchip,
            String status,
            UUID ownerId,
            String ownerFullName,
            String ownerDocumentType,
            String ownerDocumentNumber) { }

    record PatientPage(
            List<PatientSummary> content,
            int page,
            int size,
            long totalElements,
            int totalPages) { }
}
