package com.petcare.enterprise.identity.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

record UserVersionRequest(@NotNull @PositiveOrZero Long version) {
}
