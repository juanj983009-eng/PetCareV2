package com.petcare.enterprise.identity.infrastructure.bootstrap;

import com.petcare.enterprise.tenancy.domain.TenantPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("petcare.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        String tenantName,
        String tenantSlug,
        TenantPlan tenantPlan,
        String branchName,
        String branchTimeZone,
        String adminFirstName,
        String adminLastName,
        String adminEmail,
        String adminPassword) {
}
