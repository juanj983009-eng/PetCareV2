package com.petcare.enterprise.clinical.presentation;

import com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSearch;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.SortDirection;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.SortField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/owners")
class OwnerController {
    private final OwnerSearchUseCase owners;
    private final OwnerRegistrationUseCase registration;

    OwnerController(OwnerSearchUseCase owners, OwnerRegistrationUseCase registration) {
        this.owners = owners;
        this.registration = registration;
    }

    @GetMapping
    OwnerPageResponse search(
            @RequestParam @Size(min = 2, max = 100) @Pattern(regexp = "(?s).*\\S.*\\S.*") String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") @Pattern(regexp = "name|document|phone") String sortBy,
            @RequestParam(defaultValue = "asc") @Pattern(regexp = "asc|desc") String direction) {
        return OwnerPageResponse.from(owners.search(new OwnerSearch(
                page,
                size,
                query,
                sortField(sortBy),
                sortDirection(direction))));
    }

    @PostMapping
    ResponseEntity<OwnerDetailResponse> create(@Valid @RequestBody CreateOwnerRequest request) {
        var created = registration.register(request.toCommand(), request.confirmPhoneDuplicate());
        return ResponseEntity.created(URI.create("/api/v1/owners/" + created.id()))
                .body(OwnerDetailResponse.from(created));
    }

    @PutMapping("/{ownerId}")
    OwnerDetailResponse update(@PathVariable UUID ownerId, @Valid @RequestBody UpdateOwnerRequest request) {
        var updated = registration.update(ownerId, request.toCommand(), request.confirmPhoneDuplicate());
        return OwnerDetailResponse.from(updated);
    }

    private static SortField sortField(String value) {
        return switch (String.valueOf(value)) {
            case "name" -> SortField.NAME;
            case "document" -> SortField.DOCUMENT;
            case "phone" -> SortField.PHONE;
            default -> throw new IllegalArgumentException("Invalid owner sort");
        };
    }

    private static SortDirection sortDirection(String value) {
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "asc" -> SortDirection.ASC;
            case "desc" -> SortDirection.DESC;
            default -> throw new IllegalArgumentException("Invalid owner direction");
        };
    }
}
