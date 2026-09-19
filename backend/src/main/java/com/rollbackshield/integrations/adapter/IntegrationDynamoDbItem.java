package com.rollbackshield.integrations.adapter;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Single-table item for an Integration. pk=sk=INTEGRATION#id; gsi1
 * (ORG#orgId) lists an organization's integrations without a Scan (§34).
 * Connection and sync state are stored as flat attributes mirroring the
 * IntegrationConnection/SyncState value objects.
 */
@DynamoDbBean
public class IntegrationDynamoDbItem {

    private String pk;
    private String sk;
    private String gsi1pk;
    private String gsi1sk;
    private String integrationId;
    private String organizationId;
    private String name;
    private String connectorType;
    private String endpoint;
    private String credentialKind;
    private String credentialSecretReference;
    private String credentialRoleArn;
    private String credentialExternalId;
    private String configurationJson;
    private String connectionState;
    private String connectionHealthState;
    private String connectionHealthDetail;
    private String connectionHealthCheckedAt;
    private String connectionLastError;
    private String connectionStateChangedAt;
    private String syncStatus;
    private String syncLastAttemptedAt;
    private String syncLastSuccessfulAt;
    private Long syncLastDiscoveredCount;
    private String syncLastError;
    private String createdAt;
    private String updatedAt;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    @DynamoDbSecondaryPartitionKey(indexNames = "gsi1")
    public String getGsi1pk() { return gsi1pk; }
    public void setGsi1pk(String gsi1pk) { this.gsi1pk = gsi1pk; }

    @DynamoDbSecondarySortKey(indexNames = "gsi1")
    public String getGsi1sk() { return gsi1sk; }
    public void setGsi1sk(String gsi1sk) { this.gsi1sk = gsi1sk; }

    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getConnectorType() { return connectorType; }
    public void setConnectorType(String connectorType) { this.connectorType = connectorType; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getCredentialKind() { return credentialKind; }
    public void setCredentialKind(String credentialKind) { this.credentialKind = credentialKind; }

    public String getCredentialSecretReference() { return credentialSecretReference; }
    public void setCredentialSecretReference(String value) { this.credentialSecretReference = value; }

    public String getCredentialRoleArn() { return credentialRoleArn; }
    public void setCredentialRoleArn(String value) { this.credentialRoleArn = value; }

    public String getCredentialExternalId() { return credentialExternalId; }
    public void setCredentialExternalId(String value) { this.credentialExternalId = value; }

    public String getConfigurationJson() { return configurationJson; }
    public void setConfigurationJson(String value) { this.configurationJson = value; }

    public String getConnectionState() { return connectionState; }
    public void setConnectionState(String connectionState) { this.connectionState = connectionState; }

    public String getConnectionHealthState() { return connectionHealthState; }
    public void setConnectionHealthState(String value) { this.connectionHealthState = value; }

    public String getConnectionHealthDetail() { return connectionHealthDetail; }
    public void setConnectionHealthDetail(String value) { this.connectionHealthDetail = value; }

    public String getConnectionHealthCheckedAt() { return connectionHealthCheckedAt; }
    public void setConnectionHealthCheckedAt(String value) { this.connectionHealthCheckedAt = value; }

    public String getConnectionLastError() { return connectionLastError; }
    public void setConnectionLastError(String value) { this.connectionLastError = value; }

    public String getConnectionStateChangedAt() { return connectionStateChangedAt; }
    public void setConnectionStateChangedAt(String value) { this.connectionStateChangedAt = value; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public String getSyncLastAttemptedAt() { return syncLastAttemptedAt; }
    public void setSyncLastAttemptedAt(String value) { this.syncLastAttemptedAt = value; }

    public String getSyncLastSuccessfulAt() { return syncLastSuccessfulAt; }
    public void setSyncLastSuccessfulAt(String value) { this.syncLastSuccessfulAt = value; }

    public Long getSyncLastDiscoveredCount() { return syncLastDiscoveredCount; }
    public void setSyncLastDiscoveredCount(Long value) { this.syncLastDiscoveredCount = value; }

    public String getSyncLastError() { return syncLastError; }
    public void setSyncLastError(String value) { this.syncLastError = value; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
