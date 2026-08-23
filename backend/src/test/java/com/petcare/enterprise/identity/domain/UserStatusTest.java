package com.petcare.enterprise.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserStatusTest {

    @Test
    void onlyActiveUsersCanAuthenticate() {
        assertThat(UserStatus.ACTIVO.canAuthenticate()).isTrue();
        assertThat(UserStatus.BLOQUEADO.canAuthenticate()).isFalse();
        assertThat(UserStatus.INACTIVO.canAuthenticate()).isFalse();
    }
}
