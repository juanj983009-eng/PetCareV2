package com.petcare.enterprise.identity.infrastructure.security;

import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorCode;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseFactory;
import com.petcare.enterprise.shared.infrastructure.web.error.ApiErrorResponseWriter;
import com.petcare.enterprise.tenancy.application.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiErrorResponseFactory errorFactory,
            ApiErrorResponseWriter errorWriter,
            TenantContext tenantContext) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/platform/tenants").hasAuthority("PLATFORM_TENANT_CREATE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/**")
                            .hasAuthority("USER_READ")
                        .requestMatchers("/api/v1/users", "/api/v1/users/**")
                            .hasAuthority("USER_MANAGE")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(errorFactory, errorWriter))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                response,
                                HttpStatus.FORBIDDEN,
                                errorFactory.create(
                                        ApiErrorCode.ACCESS_DENIED,
                                        "No tienes permiso para realizar esta operación."))))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint(errorFactory, errorWriter))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new PetCareJwtAuthenticationConverter())))
                .addFilterBefore(new TenantMdcFilter(tenantContext), AuthorizationFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(
            ApiErrorResponseFactory errorFactory,
            ApiErrorResponseWriter errorWriter) {
        return (request, response, exception) -> errorWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                errorFactory.create(
                        ApiErrorCode.UNAUTHORIZED,
                        "Debes autenticarte para continuar."));
    }

}
