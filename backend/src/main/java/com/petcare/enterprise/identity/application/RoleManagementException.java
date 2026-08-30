package com.petcare.enterprise.identity.application;

public final class RoleManagementException extends RuntimeException {

    private final Kind kind;

    private RoleManagementException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public static RoleManagementException roleNotFound() { return new RoleManagementException(Kind.ROLE_NOT_FOUND); }
    public static RoleManagementException permissionNotFound() { return new RoleManagementException(Kind.PERMISSION_NOT_FOUND); }
    public static RoleManagementException userNotFound() { return new RoleManagementException(Kind.USER_NOT_FOUND); }
    public static RoleManagementException protectedAdministrator() { return new RoleManagementException(Kind.PROTECTED_ADMINISTRATOR); }
    public static RoleManagementException concurrentConflict() { return new RoleManagementException(Kind.CONCURRENT_CONFLICT); }

    public Kind kind() { return kind; }

    public enum Kind { ROLE_NOT_FOUND, PERMISSION_NOT_FOUND, USER_NOT_FOUND, PROTECTED_ADMINISTRATOR, CONCURRENT_CONFLICT }
}
