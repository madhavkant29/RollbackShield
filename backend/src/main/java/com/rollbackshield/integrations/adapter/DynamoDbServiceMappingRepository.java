package com.rollbackshield.integrations.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.domain.ServiceMappingId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** DynamoDB adapter for ServiceMappingRepository; bindings stored as JSON. */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbServiceMappingRepository implements ServiceMappingRepository {

    private final DynamoDbTable<ServiceMappingDynamoDbItem> table;
    private final ObjectMapper mapper;

    public DynamoDbServiceMappingRepository(DynamoDbEnhancedClient enhancedClient,
                                            @Value("${rollbackshield.dynamodb.table-name}") String tableName,
                                            ObjectMapper mapper) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(ServiceMappingDynamoDbItem.class));
        this.mapper = mapper;
    }

    @Override
    public ServiceMapping save(ServiceMapping mapping) {
        table.putItem(toItem(mapping, mapper));
        return mapping;
    }

    @Override
    public Optional<ServiceMapping> findByService(ServiceId serviceId) {
        String key = "SERVICEMAPPING#" + serviceId;
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(key).sortValue(key).build()))
            .map(item -> toDomain(item, mapper));
    }

    @Override
    public List<ServiceMapping> findByOrganization(OrganizationId organizationId) {
        return table.index("gsi1")
            // Shared gsi1pk with integrations/services: prefix-filter to this type.
            .query(QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue("ORG#" + organizationId).sortValue("SERVICEMAPPING#").build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .map(item -> toDomain(item, mapper))
            .collect(Collectors.toList());
    }

    static ServiceMappingDynamoDbItem toItem(ServiceMapping mapping, ObjectMapper mapper) {
        ServiceMappingDynamoDbItem item = new ServiceMappingDynamoDbItem();
        String key = "SERVICEMAPPING#" + mapping.serviceId();
        item.setPk(key);
        item.setSk(key);
        item.setGsi1pk("ORG#" + mapping.organizationId());
        item.setGsi1sk(key);
        item.setMappingId(mapping.id().toString());
        item.setOrganizationId(mapping.organizationId().toString());
        item.setServiceId(mapping.serviceId().toString());
        item.setBindingsJson(DynamoDbIntegrationRepository.writeJson(mapper, mapping.bindings()));
        item.setCreatedAt(mapping.createdAt().toString());
        item.setUpdatedAt(mapping.updatedAt().toString());
        return item;
    }

    static ServiceMapping toDomain(ServiceMappingDynamoDbItem item, ObjectMapper mapper) {
        return new ServiceMapping(
            ServiceMappingId.of(item.getMappingId()),
            OrganizationId.of(item.getOrganizationId()),
            ServiceId.of(item.getServiceId()),
            readBindings(mapper, item.getBindingsJson()),
            Instant.parse(item.getCreatedAt()),
            Instant.parse(item.getUpdatedAt()));
    }

    private static List<ResourceBinding> readBindings(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<ResourceBinding>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize service mapping bindings", e);
        }
    }
}
