package com.petcare.enterprise.identity.infrastructure.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "petcare.bootstrap", name = "enabled", havingValue = "true")
class IdentityBootstrapRunner implements ApplicationRunner {

    private final IdentityBootstrapService service;

    IdentityBootstrapRunner(IdentityBootstrapService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.run();
    }
}
