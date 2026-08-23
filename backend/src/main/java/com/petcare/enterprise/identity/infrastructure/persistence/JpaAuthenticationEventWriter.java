package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.AuthenticationEvent;
import com.petcare.enterprise.identity.application.AuthenticationEventWriter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaAuthenticationEventWriter implements AuthenticationEventWriter {

    private final SpringDataAuthenticationEventRepository repository;

    JpaAuthenticationEventWriter(SpringDataAuthenticationEventRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(AuthenticationEvent event) {
        repository.saveAndFlush(AuthenticationEventJpaEntity.from(event));
    }
}
