package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerSearchUseCase;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSearch;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.SortDirection;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.SortField;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/owners")
class OwnerController {
    private final OwnerSearchUseCase owners;
    OwnerController(OwnerSearchUseCase owners) { this.owners = owners; }

    @GetMapping
    OwnerPageResponse search(@RequestParam @Size(min = 2, max = 100) @Pattern(regexp = "(?s).*\\S.*\\S.*") String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|document|phone") String sortBy,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        return OwnerPageResponse.from(owners.search(new OwnerSearch(page, size, query, sortField(sortBy), sortDirection(direction))));
    }
    private static SortField sortField(String value) { return switch (String.valueOf(value)) { case "name" -> SortField.NAME; case "document" -> SortField.DOCUMENT; case "phone" -> SortField.PHONE; default -> throw new IllegalArgumentException("Invalid owner sort"); }; }
    private static SortDirection sortDirection(String value) { return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) { case "asc" -> SortDirection.ASC; case "desc" -> SortDirection.DESC; default -> throw new IllegalArgumentException("Invalid owner direction"); }; }
}
