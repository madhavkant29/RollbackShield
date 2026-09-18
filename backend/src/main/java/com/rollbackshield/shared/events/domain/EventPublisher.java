package com.rollbackshield.shared.events.domain;

public interface EventPublisher {
    void publish(DomainEvent event);
}
