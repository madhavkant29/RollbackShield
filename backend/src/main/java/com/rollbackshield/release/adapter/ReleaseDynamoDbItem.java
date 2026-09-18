package com.rollbackshield.release.adapter;

import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Single-table item for a Release. Primary key lets us fetch a release
 * directly by id; gsi1 (pk=SERVICE#serviceId) supports "list releases for a
 * service" without a Scan (§34).
 */
@DynamoDbBean
public class ReleaseDynamoDbItem {

    private String pk;              // "RELEASE#<releaseId>"
    private String sk;              // "RELEASE#<releaseId>"
    private String gsi1pk;          // "SERVICE#<serviceId>"
    private String gsi1sk;          // "RELEASE#<releaseId>"
    private String releaseId;
    private String organizationId;
    private String serviceId;
    private String previousVersionLabel;
    private String candidateVersionLabel;
    private String state;
    private long epoch;
    private String createdAt;
    private String updatedAt;
    private Long version; // DynamoDB Enhanced Client optimistic-lock attribute

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

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getPreviousVersionLabel() { return previousVersionLabel; }
    public void setPreviousVersionLabel(String previousVersionLabel) { this.previousVersionLabel = previousVersionLabel; }

    public String getCandidateVersionLabel() { return candidateVersionLabel; }
    public void setCandidateVersionLabel(String candidateVersionLabel) { this.candidateVersionLabel = candidateVersionLabel; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    @DynamoDbVersionAttribute
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
