package com.petcare.enterprise.identity.application;

public interface AuthenticationEventWriter {

    void append(AuthenticationEvent event);
}
