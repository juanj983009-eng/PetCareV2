package com.petcare.enterprise.identity.application;

import java.util.UUID;

public record LoginCommand(String clinicSlug, String email, String password, UUID correlationId) {
}
