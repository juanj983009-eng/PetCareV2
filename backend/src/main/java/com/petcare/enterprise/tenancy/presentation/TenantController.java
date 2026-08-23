package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.tenancy.application.RegisterTenantCommand;
import com.petcare.enterprise.tenancy.application.RegisterTenantUseCase;
import com.petcare.enterprise.tenancy.application.RegisteredTenant;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
class TenantController {

    static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final RegisterTenantUseCase registerTenantUseCase;

    TenantController(RegisterTenantUseCase registerTenantUseCase) {
        this.registerTenantUseCase = registerTenantUseCase;
    }

    @PostMapping
    ResponseEntity<CreateTenantResponse> create(
            @Valid @RequestBody CreateTenantRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationHeader) {
        UUID correlationId = CorrelationIds.resolve(correlationHeader);
        RegisteredTenant registered = registerTenantUseCase.register(new RegisterTenantCommand(
                request.name(),
                request.ruc(),
                request.slug(),
                request.plan(),
                request.status(),
                request.mainBranch().name(),
                request.mainBranch().address(),
                request.mainBranch().phone(),
                request.mainBranch().timeZone(),
                correlationId));

        URI location = URI.create("/api/v1/platform/tenants/" + registered.id());
        return ResponseEntity.created(location)
                .header(CORRELATION_HEADER, correlationId.toString())
                .body(CreateTenantResponse.from(registered));
    }
}
