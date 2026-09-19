package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQS: discovers real queues and their operational attributes. Epoch fencing
 * itself is enforced by the control plane's work-fence (see
 * {@code workfence/}), which the SQS work queue adapter carries job metadata
 * through -- this provider's job is to make the queues visible and
 * attribute-bound, not to re-implement fencing vendor-specifically.
 */
@Component
public class SqsQueueAdapter implements CapabilityProvider, DiscoveryContributionPort {

    /** Attribute lookups are one call per queue; bound them so sync stays cheap. */
    private static final int MAX_ATTRIBUTE_LOOKUPS = 50;

    private final AwsClients clients;

    public SqsQueueAdapter(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.QUEUE_DISCOVERY);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        SqsClient sqs = clients.sqs(context);
        List<String> queueUrls = new ArrayList<>(sqs.listQueuesPaginator().queueUrls().stream().toList());
        List<DiscoveredResource> found = new ArrayList<>();
        for (int index = 0; index < queueUrls.size(); index++) {
            String queueUrl = queueUrls.get(index);
            String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("queueUrl", queueUrl);
            if (index < MAX_ATTRIBUTE_LOOKUPS) {
                try {
                    Map<String, String> attributes = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl).attributeNames(QueueAttributeName.ALL).build())
                        .attributesAsStrings();
                    metadata.put("approximateMessages",
                        attributes.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES.toString(), "0"));
                    metadata.put("approximateNotVisible", attributes.getOrDefault(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE.toString(), "0"));
                    metadata.put("visibilityTimeout",
                        attributes.getOrDefault(QueueAttributeName.VISIBILITY_TIMEOUT.toString(), "0"));
                    metadata.put("hasRedrivePolicy",
                        String.valueOf(attributes.containsKey(QueueAttributeName.REDRIVE_POLICY.toString())));
                } catch (RuntimeException e) {
                    metadata.put("attributesError", safeMessage(e));
                }
            } else {
                metadata.put("attributesSampled", "false");
            }
            found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
                DiscoveredResourceType.QUEUE, queueUrl, queueName, clients.region(context).id(), metadata));
        }
        return found;
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
