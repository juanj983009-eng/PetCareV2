package com.petcare.enterprise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PetCareEnterpriseApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetCareEnterpriseApplication.class, args);
    }
}
