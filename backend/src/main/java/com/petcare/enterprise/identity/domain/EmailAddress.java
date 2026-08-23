package com.petcare.enterprise.identity.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmailAddress(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public EmailAddress {
        Objects.requireNonNull(value, "Email is required");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 180 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Email has an invalid format");
        }
    }
}
