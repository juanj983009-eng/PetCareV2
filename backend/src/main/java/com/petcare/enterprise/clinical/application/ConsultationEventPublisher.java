package com.petcare.enterprise.clinical.application;

import com.petcare.enterprise.clinical.domain.ConsultationFinalized;

public interface ConsultationEventPublisher {

    void publish(ConsultationFinalized event);
}
