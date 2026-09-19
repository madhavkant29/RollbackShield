package com.rollbackshield.integrations.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.integrations.domain.ConnectionState;
import com.rollbackshield.integrations.domain.ConnectorHealth;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationConnection;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.integrations.domain.SyncState;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DynamoDB adapter for IntegrationRepository, implementing the same port
 * as InMemoryIntegrationRepository. Single-table: PK/SK =
 * INTEGRATION#id; gsi1 (ORG#orgId) for organization listing without a Scan.
 */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbIntegrationRepository implements IntegrationRepository {

    private final DynamoDbTable<IntegrationDynamoDbItem> table;
    private final ObjectMapper mapper;

    public DynamoDbIntegrationRepository(DynamoDbEnhancedClient enhancedClient,
                                         @Value("${rollbackshield.dynamodb.table-name}") String tableName,
                                         ObjectMapper mapper) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(IntegrationDynamoDbItem.class));
        this.mapper = mapper;
    }

    @Override
    public Integration save(Integration integration) {
        table.putItem(toItem(integration, mapper));
        return integration;
    }

    @Override
    public Optional<Integration> findById(IntegrationId id) {
        String key = "INTEGRATION#" + id;
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(key).sortValue(key).build()))
            .map(item -> toDomain(item, mapper));
    }

    @Override
    public List<Integration> findByOrganization(OrganizationId organizationId) {
        return table.index("gsi1")
            // Same shared gsi1pk as other org-scoped items: restrict to this
            // entity's sort prefix so foreign items are never mapped here.
            .query(QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue("ORG#" + organizationId).sortValue("INTEGRATION#").build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .map(item -> toDomain(item, mapper))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(IntegrationId id) {
        String key = "INTEGRATION#" + id;
        table.deleteItem(Key.builder().partitionValue(key).sortValue(key).build());
    }

    static IntegrationDynamoDbItem toItem(Integration integration, ObjectMapper mapper) {
        IntegrationDynamoDbItem item = new IntegrationDynamoDbItem();
        String key = "INTEGRATION#" + integration.id();
        item.setPk(key);
        item.setSk(key);
        item.setGsi1pk("ORG#" + integration.organizationId());
        item.setGsi1sk(key);
        item.setIntegrationId(integration.id().toString());
        item.setOrganizationId(integration.organizationId().toString());
        item.setName(integration.name());
        item.setConnectorType(integration.type().name());
        item.setEndpoint(integration.endpoint());
        item.setCredentialKind(integration.credential().kind().name());
        item.setCredentialSecretReference(integration.credential().secretReference());
        item.setCredentialRoleArn(integration.credential().roleArn());
        item.setCredentialExternalId(integration.credential().externalId());
        item.setConfigurationJson(writeJson(mapper, integration.configuration()));
        item.setConnectionState(integration.connectionState().name());
        item.setConnectionHealthState(integration.health().state().name());
        item.setConnectionHealthDetail(integration.health().detail());
        item.setConnectionHealthCheckedAt(toString(integration.health().checkedAt()));
        item.setConnectionLastError(integration.connection().lastError());
        item.setConnectionStateChangedAt(toString(integration.connection().stateChangedAt()));
        item.setSyncStatus(integration.syncState().status().name());
        item.setSyncLastAttemptedAt(toString(integration.syncState().lastAttemptedAt()));
        item.setSyncLastSuccessfulAt(toString(integration.syncState().lastSuccessfulAt()));
        item.setSyncLastDiscoveredCount((long) integration.syncState().lastDiscoveredCount());
        item.setSyncLastError(integration.syncState().lastError());
        item.setCreatedAt(integration.createdAt().toString());
        item.setUpdatedAt(integration.updatedAt().toString());
        return item;
    }

    static Integration toDomain(IntegrationDynamoDbItem item, ObjectMapper mapper) {
        IntegrationCredentialReference credential = new IntegrationCredentialReference(
            IntegrationCredentialReference.CredentialKind.valueOf(item.getCredentialKind()),
            item.getCredentialSecretReference(), item.getCredentialRoleArn(), item.getCredentialExternalId());
        IntegrationConnection connection = new IntegrationConnection(
            ConnectionState.valueOf(item.getConnectionState()),
            new ConnectorHealth(ConnectorHealth.HealthState.valueOf(item.getConnectionHealthState()),
                item.getConnectionHealthDetail(), parseInstant(item.getConnectionHealthCheckedAt())),
            item.getConnectionLastError(), parseInstant(item.getConnectionStateChangedAt()));
        SyncState syncState = new SyncState(
            SyncState.SyncStatus.valueOf(item.getSyncStatus()),
            parseInstant(item.getSyncLastAttemptedAt()),
            parseInstant(item.getSyncLastSuccessfulAt()),
            item.getSyncLastDiscoveredCount() == null ? 0 : item.getSyncLastDiscoveredCount().intValue(),
            item.getSyncLastError());
        return new Integration(
            IntegrationId.of(item.getIntegrationId()),
            OrganizationId.of(item.getOrganizationId()),
            item.getName(),
            ConnectorType.valueOf(item.getConnectorType()),
            item.getEndpoint(),
            credential,
            readJsonMap(mapper, item.getConfigurationJson()),
            connection,
            syncState,
            Instant.parse(item.getCreatedAt()),
            Instant.parse(item.getUpdatedAt()));
    }

    static String writeJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize integration configuration", e);
        }
    }

    static Map<String, String> readJsonMap(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize integration configuration", e);
        }
    }

    static String toString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
