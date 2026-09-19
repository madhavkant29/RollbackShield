package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBus;
import software.amazon.awssdk.services.eventbridge.model.ListEventBusesRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** EventBridge: discovers event buses that domain events travel through. */
@Component
public class EventBridgeDiscoveryAdapter implements CapabilityProvider, DiscoveryContributionPort {

    private final AwsClients clients;

    public EventBridgeDiscoveryAdapter(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.EVENT_DISCOVERY);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        EventBridgeClient events = clients.events(context);
        List<DiscoveredResource> found = new ArrayList<>();
        for (EventBus bus : events.listEventBuses(ListEventBusesRequest.builder().build()).eventBuses()) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("busArn", bus.arn());
            metadata.put("policy", bus.policy() == null ? "" : "present");
            found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
                DiscoveredResourceType.EVENT_BUS, bus.name(), bus.name(),
                clients.region(context).id(), metadata));
        }
        return found;
    }
}
