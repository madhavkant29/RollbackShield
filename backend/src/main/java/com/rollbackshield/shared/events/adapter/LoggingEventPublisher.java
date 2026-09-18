package com.rollbackshield.shared.events.adapter;

import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local-dev event "bus": just logs. Swapped for EventBridgeEventPublisher under the aws profile. */
@Component
@ConditionalOnProperty(name = "rollbackshield.events", havingValue = "logging", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("domain-event type={} release={} org={} payload={}",
            event.eventType(), event.releaseId(), event.organizationId(), event.payload());
    }
}
