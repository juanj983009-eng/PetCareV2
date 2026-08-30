package com.petcare.enterprise.identity.presentation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

record ReplaceUserRolesRequest(@NotNull List<@NotNull UUID> roleIds, @Min(0) long version) {
}
