package com.petcare.enterprise.identity.application;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(TokenSubject subject);
}
