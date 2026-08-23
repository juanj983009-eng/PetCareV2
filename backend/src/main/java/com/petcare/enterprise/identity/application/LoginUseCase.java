package com.petcare.enterprise.identity.application;

public interface LoginUseCase {

    LoginResult login(LoginCommand command);
}
