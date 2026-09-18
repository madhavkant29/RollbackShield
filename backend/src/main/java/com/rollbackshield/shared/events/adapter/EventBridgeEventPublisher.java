package com.rollbackshield.shared.events.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

@Component
@ConditionalOnProperty(name = "rollbackshield.events", havingValue = "eventbridge")
public class EventBridgeEventPublisher implements EventPublisher {

    private final EventBridgeClient client;
    private final ObjectMapper objectMapper;
    private final String busName;

    public EventBridgeEventPublisher(EventBridgeClient client, ObjectMapper objectMapper,
                                      @Value("${rollbackshield.eventbridge.bus-name}") String busName) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.busName = busName;
    }

    @Override
    public void publish(DomainEvent event) {
        try {
            String detail = objectMapper.writeValueAsString(event);
            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(busName)
                .source("rollbackshield.control-plane")
                .detailType(event.eventType())
                .detail(detail)
                .build();
            client.putEvents(PutEventsRequest.builder().entries(entry).build());
        } catch (Exception e) {
            // Domain events are best-effort telemetry, not the source of truth (audit trail is).
            // A publish failure must never fail the request that triggered it.
            org.slf4j.LoggerFactory.getLogger(EventBridgeEventPublisher.class)
                .warn("Failed to publish domain event {}", event.eventType(), e);
        }
    }
}
