package com.petcare.enterprise.identity.application;

public final class UserManagementException extends RuntimeException {

    private final Kind kind;
    private final String field;

    private UserManagementException(Kind kind, String field) {
        super(kind.name());
        this.kind = kind;
        this.field = field;
    }

    public static UserManagementException userNotFound() {
        return new UserManagementException(Kind.USER_NOT_FOUND, null);
    }

    public static UserManagementException roleNotFound() {
        return new UserManagementException(Kind.ROLE_NOT_FOUND, "roleId");
    }

    public static UserManagementException branchNotFound() {
        return new UserManagementException(Kind.BRANCH_NOT_FOUND, "branchId");
    }

    public static UserManagementException emailConflict() {
        return new UserManagementException(Kind.EMAIL_CONFLICT, "email");
    }

    public static UserManagementException concurrentConflict() {
        return new UserManagementException(Kind.CONCURRENT_CONFLICT, "version");
    }

    public static UserManagementException blockedTransition() {
        return new UserManagementException(Kind.BLOCKED_TRANSITION, "status");
    }

    public static UserManagementException invalidSort() {
        return new UserManagementException(Kind.INVALID_SORT, "sort");
    }

    public Kind kind() {
        return kind;
    }

    public String field() {
        return field;
    }

    public enum Kind {
        USER_NOT_FOUND,
        ROLE_NOT_FOUND,
        BRANCH_NOT_FOUND,
        EMAIL_CONFLICT,
        CONCURRENT_CONFLICT,
        BLOCKED_TRANSITION,
        INVALID_SORT
    }
}
