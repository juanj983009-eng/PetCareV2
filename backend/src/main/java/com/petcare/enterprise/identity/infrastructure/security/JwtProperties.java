package com.petcare.enterprise.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("petcare.security.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("PETCARE_JWT_SECRET must contain at least 32 bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("PETCARE_JWT_ISSUER is required");
        }
        issuer = issuer.trim();
        URI issuerUri;
        try {
            issuerUri = URI.create(issuer);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PETCARE_JWT_ISSUER must be a valid absolute URI", exception);
        }
        if (!issuerUri.isAbsolute()) {
            throw new IllegalArgumentException("PETCARE_JWT_ISSUER must be a valid absolute URI");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()
                || accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("PETCARE_JWT_ACCESS_TOKEN_TTL must be between 1 second and 1 hour");
        }
    }
}
