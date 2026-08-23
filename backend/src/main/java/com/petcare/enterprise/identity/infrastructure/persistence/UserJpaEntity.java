package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.BootstrapIdentity;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserAccount;
import com.petcare.enterprise.identity.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 180)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserJpaEntity() {
    }

    private UserJpaEntity(BootstrapIdentity identity) {
        this.id = UUID.randomUUID();
        this.tenantId = identity.tenantId();
        this.firstName = identity.firstName().trim();
        this.lastName = identity.lastName().trim();
        this.email = identity.email().value();
        this.passwordHash = identity.passwordHash();
        this.status = UserStatus.ACTIVO;
        this.createdAt = identity.createdAt();
    }

    static UserJpaEntity bootstrap(BootstrapIdentity identity) {
        return new UserJpaEntity(identity);
    }

    UserAccount toDomain() {
        return new UserAccount(
                id,
                tenantId,
                firstName,
                lastName,
                new EmailAddress(email),
                passwordHash,
                status,
                lastAccessAt,
                createdAt);
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }
}
