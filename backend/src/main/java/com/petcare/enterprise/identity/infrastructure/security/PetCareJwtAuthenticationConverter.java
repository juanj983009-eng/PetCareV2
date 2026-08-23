package com.petcare.enterprise.identity.infrastructure.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;

final class PetCareJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Pattern AUTHORITY = Pattern.compile("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID userId = requiredUuid(jwt.getClaimAsString(JwtTokenService.USER_ID));
            UUID tenantId = requiredUuid(jwt.getClaimAsString(JwtTokenService.TENANT_ID));
            UUID branchId = optionalUuid(jwt.getClaimAsString(JwtTokenService.BRANCH_ID));
            if (!userId.toString().equals(jwt.getSubject())) {
                throw invalidToken();
            }
            Collection<String> claim = jwt.getClaimAsStringList(JwtTokenService.AUTHORITIES);
            List<SimpleGrantedAuthority> authorities = claim == null
                    ? List.of()
                    : claim.stream()
                            .filter(value -> value != null && AUTHORITY.matcher(value).matches())
                            .distinct()
                            .sorted()
                            .map(SimpleGrantedAuthority::new)
                            .toList();
            if (claim != null && authorities.size() != claim.size()) {
                throw invalidToken();
            }
            PetCarePrincipal principal = new PetCarePrincipal(userId, tenantId, branchId, authorities);
            return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private static UUID requiredUuid(String value) {
        if (value == null) {
            throw invalidToken();
        }
        return UUID.fromString(value);
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Invalid access token"));
    }
}
