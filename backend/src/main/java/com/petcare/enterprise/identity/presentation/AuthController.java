package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.audit.application.CorrelationIdProvider;
import com.petcare.enterprise.identity.application.AuthenticatedSessionProvider;
import com.petcare.enterprise.identity.application.LoginCommand;
import com.petcare.enterprise.identity.application.LoginUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginUseCase loginUseCase;
    private final CorrelationIdProvider correlationIdProvider;
    private final AuthenticatedSessionProvider sessionProvider;

    AuthController(
            LoginUseCase loginUseCase,
            CorrelationIdProvider correlationIdProvider,
            AuthenticatedSessionProvider sessionProvider) {
        this.loginUseCase = loginUseCase;
        this.correlationIdProvider = correlationIdProvider;
        this.sessionProvider = sessionProvider;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(LoginResponse.from(loginUseCase.login(new LoginCommand(
                request.clinicSlug(),
                request.email(),
                request.password(),
                correlationIdProvider.currentCorrelationId()))));
    }

    @GetMapping("/session")
    SessionResponse session() {
        return SessionResponse.from(sessionProvider.requireSession());
    }
}
