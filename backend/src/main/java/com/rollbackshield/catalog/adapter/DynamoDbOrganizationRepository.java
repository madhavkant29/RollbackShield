package com.rollbackshield.catalog.adapter;

import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.catalog.domain.OrganizationRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbOrganizationRepository implements OrganizationRepository {

    private final DynamoDbTable<OrganizationDynamoDbItem> table;

    public DynamoDbOrganizationRepository(DynamoDbEnhancedClient enhancedClient,
                                           @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(OrganizationDynamoDbItem.class));
    }

    @Override
    public Organization save(Organization organization) {
        OrganizationDynamoDbItem item = new OrganizationDynamoDbItem();
        String key = "ORG#" + organization.id();
        item.setPk(key);
        item.setSk(key);
        item.setOrganizationId(organization.id().toString());
        item.setName(organization.name());
        item.setCreatedAt(organization.createdAt().toString());
        table.putItem(item);
        return organization;
    }

    @Override
    public Optional<Organization> findById(OrganizationId id) {
        String key = "ORG#" + id;
        OrganizationDynamoDbItem item = table.getItem(Key.builder().partitionValue(key).sortValue(key).build());
        if (item == null) {
            return Optional.empty();
        }
        return Optional.of(new Organization(OrganizationId.of(item.getOrganizationId()), item.getName(),
            Instant.parse(item.getCreatedAt())));
    }
}
