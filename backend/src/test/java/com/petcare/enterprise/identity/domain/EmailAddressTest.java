package com.petcare.enterprise.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailAddressTest {

    @Test
    void normalizesWhitespaceAndCase() {
        assertThat(new EmailAddress("  Admin@Example.TEST ").value()).isEqualTo("admin@example.test");
    }

    @Test
    void rejectsMalformedAndOversizedValues() {
        assertThatThrownBy(() -> new EmailAddress("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailAddress("a".repeat(175) + "@x.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
