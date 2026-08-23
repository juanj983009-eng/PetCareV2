package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.identity.application.AccessTokenIssuer;
import com.petcare.enterprise.identity.application.IssuedAccessToken;
import com.petcare.enterprise.identity.application.TokenSubject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
final class JwtTokenService implements AccessTokenIssuer {

    static final String USER_ID = "uid";
    static final String TENANT_ID = "tid";
    static final String BRANCH_ID = "bid";
    static final String AUTHORITIES = "authorities";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedAccessToken issue(TokenSubject subject) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject.userId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(USER_ID, subject.userId().toString())
                .claim(TENANT_ID, subject.tenantId().toString())
                .claim(AUTHORITIES, new ArrayList<>(subject.authorities()));
        if (subject.branchId() != null) {
            claims.claim(BRANCH_ID, subject.branchId().toString());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
        return new IssuedAccessToken(token, expiresAt);
    }
}
