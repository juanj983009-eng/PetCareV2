package com.petcare.enterprise.identity.presentation;

import com.petcare.enterprise.identity.application.UserManagementUseCase.UserPage;
import java.util.List;

record UserPageResponse(
        List<UserResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static UserPageResponse from(UserPage page) {
        return new UserPageResponse(
                page.content().stream().map(UserResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
