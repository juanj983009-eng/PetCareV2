package com.petcare.enterprise.tenancy.domain;

import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public final class Branch {

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final String address;
    private final String phone;
    private final ZoneId timeZone;
    private final boolean active;
    private final Instant createdAt;

    private Branch(
            UUID id,
            UUID tenantId,
            String name,
            String address,
            String phone,
            ZoneId timeZone,
            boolean active,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Branch id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Branch tenant id is required");
        this.name = Tenant.requiredText(name, 120, "Branch name");
        this.address = Tenant.optionalText(address, 250, "Branch address");
        this.phone = Tenant.optionalText(phone, 30, "Branch phone");
        this.timeZone = Objects.requireNonNull(timeZone, "Branch time zone is required");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "Branch creation time is required");
    }

    public static Branch createMain(
            UUID id,
            UUID tenantId,
            String name,
            String address,
            String phone,
            String timeZone,
            Instant createdAt) {
        return new Branch(id, tenantId, name, address, phone, parseZoneId(timeZone), true, createdAt);
    }

    public static Branch restore(
            UUID id,
            UUID tenantId,
            String name,
            String address,
            String phone,
            String timeZone,
            boolean active,
            Instant createdAt) {
        return new Branch(id, tenantId, name, address, phone, parseZoneId(timeZone), active, createdAt);
    }

    private static ZoneId parseZoneId(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("Branch time zone is required");
        }
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            throw new DomainValidationException("Branch time zone is invalid");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public String phone() {
        return phone;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
