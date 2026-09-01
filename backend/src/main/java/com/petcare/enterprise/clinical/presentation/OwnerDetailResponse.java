package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.Owner;
import java.util.UUID;
record OwnerDetailResponse(UUID id, String firstName, String lastName, String documentType, String documentNumber, String phone, String email, String address, boolean notificationsAccepted, boolean active) {
    static OwnerDetailResponse from(Owner value) { return new OwnerDetailResponse(value.id(), value.firstName(), value.lastName(), value.documentType(), value.documentNumber(), value.phone(), value.email(), value.address(), value.notificationsAccepted(), value.active()); }
}
