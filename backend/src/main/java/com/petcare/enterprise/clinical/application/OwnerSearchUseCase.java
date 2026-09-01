package com.petcare.enterprise.clinical.application;

import java.util.List;
import java.util.UUID;

public interface OwnerSearchUseCase {
    OwnerPage search(OwnerSearch query);

    record OwnerSearch(int page, int size, String query, SortField sortBy, SortDirection direction) { }
    enum SortField { NAME, DOCUMENT, PHONE }
    enum SortDirection { ASC, DESC }
    record OwnerSummary(UUID id, String firstName, String lastName, String documentType, String documentNumber, String phone) { }
    record OwnerPage(List<OwnerSummary> content, int page, int size, long totalElements, int totalPages) { }
}
