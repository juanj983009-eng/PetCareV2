package com.petcare.enterprise.tenancy.presentation;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.tenancy.application.RegisterTenantCommand;
import com.petcare.enterprise.tenancy.application.RegisterTenantUseCase;
import com.petcare.enterprise.tenancy.application.RegisteredTenant;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
class TenantController {

    static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final RegisterTenantUseCase registerTenantUseCase;
    private final CorrelationIdProvider correlationIdProvider;

    TenantController(RegisterTenantUseCase registerTenantUseCase, CorrelationIdProvider correlationIdProvider) {
        this.registerTenantUseCase = registerTenantUseCase;
        this.correlationIdProvider = correlationIdProvider;
    }

    @PostMapping
    ResponseEntity<CreateTenantResponse> create(
            @Valid @RequestBody CreateTenantRequest request) {
        UUID correlationId = correlationIdProvider.currentCorrelationId();
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
