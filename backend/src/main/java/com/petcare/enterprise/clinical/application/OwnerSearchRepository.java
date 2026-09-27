package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerPage;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSearch;
import java.util.Optional;
import java.util.UUID;

public interface OwnerSearchRepository {
    OwnerPage search(UUID tenantId, OwnerSearch query);
    Optional<OwnerRegistrationUseCase.Owner> findById(UUID tenantId, UUID ownerId);
    boolean documentExists(UUID tenantId, String documentNumber);
    boolean documentExistsExcluding(UUID tenantId, String documentNumber, UUID excludedOwnerId);
    boolean phoneExists(UUID tenantId, String phone);
    boolean phoneExistsExcluding(UUID tenantId, String phone, UUID excludedOwnerId);
    OwnerRegistrationUseCase.Owner create(UUID tenantId, OwnerRegistrationUseCase.NewOwner owner);
    OwnerRegistrationUseCase.Owner update(UUID tenantId, UUID ownerId, OwnerRegistrationUseCase.NewOwner owner, boolean active);
}
