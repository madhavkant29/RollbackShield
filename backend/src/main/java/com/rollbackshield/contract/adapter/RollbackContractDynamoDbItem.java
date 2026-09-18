package com.rollbackshield.contract.adapter;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Rules are stored as a JSON string (rulesJson) rather than modeled as native
 * DynamoDB attributes. This is a deliberate v0.1 simplification: writing a
 * full AttributeConverter for the sealed CompatibilityRule hierarchy is real
 * work with little payoff before the rule set stabilizes post-hackathon --
 * see docs/adr/004-control-plane-storage.md. Everything else (keys, status,
 * version, hash) is a native attribute and query-able normally.
 */
@DynamoDbBean
public class RollbackContractDynamoDbItem {
    private String pk;      // "RELEASE#<releaseId>"
    private String sk;      // "CONTRACT#<contractId>"
    private String gsi1pk;  // "CONTRACT#<contractId>"
    private String gsi1sk;  // "CONTRACT#<contractId>"
    private String contractId;
    private String organizationId;
    private String serviceId;
    private String releaseId;
    private int contractVersion;
    private long policyVersion;
    private String createdAt;
    private String activatedAt;
    private long rollbackWindowSeconds;
    private String rulesJson;
    private boolean candidateEpochRequiredForAsyncWork;
    private String status;
    private String contentHash;

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
    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = contractId; }
    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public int getContractVersion() { return contractVersion; }
    public void setContractVersion(int contractVersion) { this.contractVersion = contractVersion; }
    public long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(long policyVersion) { this.policyVersion = policyVersion; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getActivatedAt() { return activatedAt; }
    public void setActivatedAt(String activatedAt) { this.activatedAt = activatedAt; }
    public long getRollbackWindowSeconds() { return rollbackWindowSeconds; }
    public void setRollbackWindowSeconds(long rollbackWindowSeconds) { this.rollbackWindowSeconds = rollbackWindowSeconds; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    public boolean isCandidateEpochRequiredForAsyncWork() { return candidateEpochRequiredForAsyncWork; }
    public void setCandidateEpochRequiredForAsyncWork(boolean v) { this.candidateEpochRequiredForAsyncWork = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
}
