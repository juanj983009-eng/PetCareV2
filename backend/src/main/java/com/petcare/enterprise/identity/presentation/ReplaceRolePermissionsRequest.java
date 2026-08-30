package com.petcare.enterprise.identity.presentation;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

record ReplaceRolePermissionsRequest(@NotNull List<@NotNull UUID> permissionIds) {
}
