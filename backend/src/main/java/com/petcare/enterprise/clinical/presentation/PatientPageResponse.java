package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientPage;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientSummary;
import java.util.List;
import java.util.UUID;

record PatientPageResponse(
        List<PatientSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static PatientPageResponse from(PatientPage page) {
        return new PatientPageResponse(
                page.content().stream().map(PatientSummaryResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    record PatientSummaryResponse(
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
            String ownerDocumentNumber) {

        static PatientSummaryResponse from(PatientSummary summary) {
            return new PatientSummaryResponse(
                    summary.id(),
                    summary.name(),
                    summary.species(),
                    summary.breed(),
                    summary.sex(),
                    summary.microchip(),
                    summary.status(),
                    summary.ownerId(),
                    summary.ownerFullName(),
                    summary.ownerDocumentType(),
                    summary.ownerDocumentNumber());
        }
    }
}
