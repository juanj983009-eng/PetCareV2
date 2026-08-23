package com.petcare.enterprise.identity.application;

public interface PasswordHasher {

    String hash(CharSequence rawPassword);

    boolean matches(CharSequence rawPassword, String passwordHash);
}
