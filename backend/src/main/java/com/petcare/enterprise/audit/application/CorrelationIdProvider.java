package com.petcare.enterprise.audit.application;

import java.util.UUID;

public interface CorrelationIdProvider {

    UUID currentCorrelationId();
}
