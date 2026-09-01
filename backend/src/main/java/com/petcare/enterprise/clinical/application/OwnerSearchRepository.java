package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerPage;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSearch;
import java.util.UUID;

public interface OwnerSearchRepository {
    OwnerPage search(UUID tenantId, OwnerSearch query);
}
