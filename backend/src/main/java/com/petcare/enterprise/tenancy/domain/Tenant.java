package com.petcare.enterprise.tenancy.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Tenant {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final UUID id;
    private final String name;
    private final String ruc;
    private final String slug;
    private final TenantPlan plan;
    private final TenantStatus status;
    private final Instant createdAt;

    private Tenant(
            UUID id,
            String name,
            String ruc,
            String slug,
            TenantPlan plan,
            TenantStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Tenant id is required");
        this.name = requiredText(name, 150, "Tenant name");
        this.ruc = optionalText(ruc, 20, "RUC");
        this.slug = validSlug(slug);
        this.plan = Objects.requireNonNull(plan, "Tenant plan is required");
        this.status = Objects.requireNonNull(status, "Tenant status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required");
    }

    public static Tenant create(
            UUID id,
            String name,
            String ruc,
            String slug,
            TenantPlan plan,
            TenantStatus status,
            Instant createdAt) {
        return new Tenant(id, name, ruc, slug, plan, status, createdAt);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String ruc() {
        return ruc;
    }

    public String slug() {
        return slug;
    }

    public TenantPlan plan() {
        return plan;
    }

    public TenantStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    static String requiredText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(fieldName + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DomainValidationException(fieldName + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private static String validSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("Tenant slug is required");
        }
        String normalized = value.trim();
        if (normalized.length() < 3 || normalized.length() > 80 || !SLUG_PATTERN.matcher(normalized).matches()) {
            throw new DomainValidationException("Tenant slug has an invalid format");
        }
        return normalized;
    }
}
