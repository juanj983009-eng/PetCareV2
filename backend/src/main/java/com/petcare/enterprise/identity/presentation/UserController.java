package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.UserManagementException;
import com.petcare.enterprise.identity.application.RoleManagementUseCase;
import com.petcare.enterprise.identity.application.UserManagementUseCase;
import com.petcare.enterprise.identity.application.UserManagementUseCase.CreateUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.SortDirection;
import com.petcare.enterprise.identity.application.UserManagementUseCase.SortField;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UpdateUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserSearch;
import com.petcare.enterprise.identity.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/users")
class UserController {

    private final UserManagementUseCase users;
    private final RoleManagementUseCase roles;

    UserController(UserManagementUseCase users, RoleManagementUseCase roles) {
        this.users = users;
        this.roles = roles;
    }

    @GetMapping
    UserPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 180) String search,
            @RequestParam(required = false) UUID roleId,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "firstName") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        return UserPageResponse.from(users.list(new UserSearch(
                page,
                size,
                search,
                roleId,
                status,
                sortField(sortBy),
                sortDirection(direction))));
    }

    @GetMapping("/options")
    UserOptionsResponse options() {
        return UserOptionsResponse.from(users.options());
    }

    @GetMapping("/{userId}")
    UserResponse get(@PathVariable UUID userId) {
        return UserResponse.from(users.get(userId));
    }

    @PostMapping
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = UserResponse.from(users.create(new CreateUser(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.initialPassword(),
                request.roleId(),
                request.branchId())));
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PutMapping("/{userId}")
    UserResponse update(@PathVariable UUID userId, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(users.update(userId, new UpdateUser(
                request.firstName(),
                request.lastName(),
                request.email(),
                request.roleId(),
                request.branchId(),
                request.version())));
    }

    @PostMapping("/{userId}/activate")
    UserResponse activate(@PathVariable UUID userId, @Valid @RequestBody UserVersionRequest request) {
        return UserResponse.from(users.activate(userId, request.version()));
    }

    @PostMapping("/{userId}/deactivate")
    UserResponse deactivate(@PathVariable UUID userId, @Valid @RequestBody UserVersionRequest request) {
        return UserResponse.from(users.deactivate(userId, request.version()));
    }

    @PutMapping("/{userId}/roles")
    UserRolesResponse replaceRoles(
            @PathVariable UUID userId,
            @Valid @RequestBody ReplaceUserRolesRequest request) {
        return UserRolesResponse.from(roles.replaceUserRoles(userId, request.roleIds(), request.version()));
    }

    private static SortField sortField(String value) {
        return switch (String.valueOf(value)) {
            case "firstName" -> SortField.FIRST_NAME;
            case "lastName" -> SortField.LAST_NAME;
            case "email" -> SortField.EMAIL;
            case "status" -> SortField.STATUS;
            case "createdAt" -> SortField.CREATED_AT;
            default -> throw UserManagementException.invalidSort();
        };
    }

    private static SortDirection sortDirection(String value) {
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "asc" -> SortDirection.ASC;
            case "desc" -> SortDirection.DESC;
            default -> throw UserManagementException.invalidSort();
        };
    }
}
