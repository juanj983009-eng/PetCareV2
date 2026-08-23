package com.petcare.enterprise.identity.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAuthenticationEventRepository
        extends JpaRepository<AuthenticationEventJpaEntity, UUID> {
}
