package com.rollbackshield.catalog.adapter;

import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.AppServiceRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
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

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbAppServiceRepository implements AppServiceRepository {

    private final DynamoDbTable<AppServiceDynamoDbItem> table;

    public DynamoDbAppServiceRepository(DynamoDbEnhancedClient enhancedClient,
                                         @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(AppServiceDynamoDbItem.class));
    }

    @Override
    public AppService save(AppService service) {
        AppServiceDynamoDbItem item = new AppServiceDynamoDbItem();
        String key = "SERVICEENTITY#" + service.id();
        item.setPk(key);
        item.setSk(key);
        item.setGsi1pk("ORG#" + service.organizationId());
        item.setGsi1sk(key);
        item.setServiceId(service.id().toString());
        item.setOrganizationId(service.organizationId().toString());
        item.setName(service.name());
        item.setCreatedAt(service.createdAt().toString());
        table.putItem(item);
        return service;
    }

    @Override
    public Optional<AppService> findById(ServiceId id) {
        String key = "SERVICEENTITY#" + id;
        AppServiceDynamoDbItem item = table.getItem(Key.builder().partitionValue(key).sortValue(key).build());
        return Optional.ofNullable(item).map(DynamoDbAppServiceRepository::toDomain);
    }

    @Override
    public List<AppService> findByOrganization(OrganizationId organizationId) {
        return table.index("gsi1")
            .query(QueryConditional.keyEqualTo(Key.builder().partitionValue("ORG#" + organizationId).build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .map(DynamoDbAppServiceRepository::toDomain)
            .collect(Collectors.toList());
    }

    private static AppService toDomain(AppServiceDynamoDbItem item) {
        return new AppService(ServiceId.of(item.getServiceId()), OrganizationId.of(item.getOrganizationId()),
            item.getName(), Instant.parse(item.getCreatedAt()));
    }
}
