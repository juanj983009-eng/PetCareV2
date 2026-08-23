package com.petcare.enterprise.tenancy.infrastructure.configuration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TenancyConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
