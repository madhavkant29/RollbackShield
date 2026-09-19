package com.rollbackshield.integrations.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.shared.domain.IntegrationId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** DynamoDB adapter for DiscoveredResourceRepository. */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbDiscoveredResourceRepository implements DiscoveredResourceRepository {

    private static final String RESOURCE_PREFIX = "RESOURCE#";

    private final DynamoDbTable<DiscoveredResourceDynamoDbItem> table;
    private final ObjectMapper mapper;

    public DynamoDbDiscoveredResourceRepository(DynamoDbEnhancedClient enhancedClient,
                                                @Value("${rollbackshield.dynamodb.table-name}") String tableName,
                                                ObjectMapper mapper) {
        this.table = enhancedClient.table(tableName,
            TableSchema.fromBean(DiscoveredResourceDynamoDbItem.class));
        this.mapper = mapper;
    }

    @Override
    public void replaceForIntegration(IntegrationId integrationId, List<DiscoveredResource> resources) {
        List<DiscoveredResourceDynamoDbItem> existing = itemsFor(integrationId);
        Set<String> keep = resources.stream().map(DiscoveredResource::resourceId).collect(Collectors.toSet());
        for (DiscoveredResourceDynamoDbItem item : existing) {
            if (!keep.contains(item.getResourceId())) {
                table.deleteItem(Key.builder().partitionValue(item.getPk()).sortValue(item.getSk()).build());
            }
        }
        resources.forEach(resource -> table.putItem(toItem(resource, mapper)));
    }

    @Override
    public List<DiscoveredResource> findByIntegration(IntegrationId integrationId) {
        return itemsFor(integrationId).stream().map(item -> toDomain(item, mapper)).toList();
    }

    @Override
    public List<DiscoveredResource> findByIntegrationAndType(IntegrationId integrationId,
                                                             DiscoveredResourceType type) {
        String partition = "INTEGRATION#" + integrationId;
        String prefix = RESOURCE_PREFIX + type.name() + "#";
        return table.query(QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue(partition).sortValue(prefix).build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .map(item -> toDomain(item, mapper))
            .collect(Collectors.toList());
    }

    @Override
    public Optional<DiscoveredResource> findByExternalId(IntegrationId integrationId,
                                                         DiscoveredResourceType type, String externalId) {
        String partition = "INTEGRATION#" + integrationId;
        String sort = RESOURCE_PREFIX + type.name() + "#" + externalId;
        return Optional.ofNullable(table.getItem(Key.builder()
                .partitionValue(partition).sortValue(sort).build()))
            .map(item -> toDomain(item, mapper));
    }

    @Override
    public int deleteNotIn(IntegrationId integrationId, List<String> resourceIds) {
        Set<String> keep = new HashSet<>(resourceIds);
        int deleted = 0;
        for (DiscoveredResourceDynamoDbItem item : itemsFor(integrationId)) {
            if (!keep.contains(item.getResourceId())) {
                table.deleteItem(Key.builder().partitionValue(item.getPk()).sortValue(item.getSk()).build());
                deleted++;
            }
        }
        return deleted;
    }

    private List<DiscoveredResourceDynamoDbItem> itemsFor(IntegrationId integrationId) {
        String partition = "INTEGRATION#" + integrationId;
        List<DiscoveredResourceDynamoDbItem> items = new ArrayList<>();
        table.query(QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue(partition).sortValue(RESOURCE_PREFIX).build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .forEach(items::add);
        return items;
    }

    static DiscoveredResourceDynamoDbItem toItem(DiscoveredResource resource, ObjectMapper mapper) {
        DiscoveredResourceDynamoDbItem item = new DiscoveredResourceDynamoDbItem();
        item.setPk("INTEGRATION#" + resource.integrationId());
        item.setSk(RESOURCE_PREFIX + resource.resourceType().name() + "#" + resource.externalId());
        item.setResourceId(resource.resourceId());
        item.setIntegrationId(resource.integrationId().toString());
        item.setConnectorType(resource.connectorType().name());
        item.setResourceType(resource.resourceType().name());
        item.setExternalId(resource.externalId());
        item.setDisplayName(resource.displayName());
        item.setRegion(resource.region());
        item.setMetadataJson(DynamoDbIntegrationRepository.writeJson(mapper, resource.metadata()));
        item.setDiscoveredAt(resource.discoveredAt().toString());
        return item;
    }

    static DiscoveredResource toDomain(DiscoveredResourceDynamoDbItem item, ObjectMapper mapper) {
        return new DiscoveredResource(
            item.getResourceId(),
            IntegrationId.of(item.getIntegrationId()),
            ConnectorType.valueOf(item.getConnectorType()),
            DiscoveredResourceType.valueOf(item.getResourceType()),
            item.getExternalId(),
            item.getDisplayName(),
            item.getRegion(),
            readMetadata(mapper, item.getMetadataJson()),
            Instant.parse(item.getDiscoveredAt()));
    }

    private static Map<String, String> readMetadata(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize resource metadata", e);
        }
    }
}
