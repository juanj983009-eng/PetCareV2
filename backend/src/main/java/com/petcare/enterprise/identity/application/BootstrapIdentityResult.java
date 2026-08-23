package com.petcare.enterprise.identity.application;

import java.util.UUID;

public record BootstrapIdentityResult(UUID userId, boolean userCreated) {
}
