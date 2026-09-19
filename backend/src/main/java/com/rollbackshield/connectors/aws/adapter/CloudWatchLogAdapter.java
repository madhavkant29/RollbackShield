package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.integrations.domain.connector.LogDiscoveryPort;
import com.rollbackshield.integrations.domain.connector.LogEvidence;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudWatch Logs: bounded operational evidence only. Log groups are
 * discoverable; error evidence is a filtered, capped query (never log
 * ingestion) so the connector cannot become a log pipeline by accident.
 */
@Component
public class CloudWatchLogAdapter implements CapabilityProvider, DiscoveryContributionPort,
    LogDiscoveryPort {

    private static final String ERROR_FILTER = "?ERROR ?Error ?error ?Exception ?exception";
    private static final int MAX_EVIDENCE = 100;

    private final AwsClients clients;

    public CloudWatchLogAdapter(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.LOG_DISCOVERY);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        return discoverLogGroups(context);
    }

    @Override
    public List<DiscoveredResource> discoverLogGroups(ConnectorContext context) {
        CloudWatchLogsClient logs = clients.logs(context);
        List<DiscoveredResource> found = new ArrayList<>();
        for (LogGroup logGroup : logs.describeLogGroupsPaginator().logGroups()) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("logGroupArn", logGroup.arn());
            metadata.put("retentionInDays",
                String.valueOf(logGroup.retentionInDays() == null ? "" : logGroup.retentionInDays()));
            metadata.put("storedBytes", String.valueOf(logGroup.storedBytes()));
            found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
                DiscoveredResourceType.LOG_GROUP, logGroup.logGroupName(), logGroup.logGroupName(),
                clients.region(context).id(), metadata));
        }
        return found;
    }

    @Override
    public List<LogEvidence> recentErrors(ConnectorContext context, String logGroupName,
                                          Instant since, int limit) {
        CloudWatchLogsClient logs = clients.logs(context);
        int capped = Math.max(1, Math.min(limit, MAX_EVIDENCE));
        List<LogEvidence> evidence = new ArrayList<>();
        var response = logs.filterLogEvents(FilterLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .startTime(since == null ? null : since.toEpochMilli())
            .filterPattern(ERROR_FILTER)
            .limit(capped)
            .build());
        response.events().forEach(event -> evidence.add(new LogEvidence(logGroupName,
            event.logStreamName(), Instant.ofEpochMilli(event.timestamp() == null ? 0 : event.timestamp()),
            truncate(event.message()))));
        return evidence;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
