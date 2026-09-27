package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientPage;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientSearch;
import java.util.UUID;

public interface PatientSearchRepository {
    PatientPage search(UUID tenantId, PatientSearch query);
}
