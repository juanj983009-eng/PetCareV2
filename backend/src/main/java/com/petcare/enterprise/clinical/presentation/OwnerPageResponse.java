package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerPage;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSummary;
import java.util.List;
import java.util.UUID;

record OwnerPageResponse(List<OwnerResponse> content, int page, int size, long totalElements, int totalPages) {
    static OwnerPageResponse from(OwnerPage page) {
        return new OwnerPageResponse(
                page.content().stream().map(OwnerResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}

record OwnerResponse(
        UUID id,
        String firstName,
        String lastName,
        String documentType,
        String documentNumber,
        String phone,
        String email,
        String address,
        boolean notificationsAccepted) {
    static OwnerResponse from(OwnerSummary owner) {
        return new OwnerResponse(
                owner.id(),
                owner.firstName(),
                owner.lastName(),
                owner.documentType(),
                owner.documentNumber(),
                owner.phone(),
                owner.email(),
                owner.address(),
                owner.notificationsAccepted());
    }
}
