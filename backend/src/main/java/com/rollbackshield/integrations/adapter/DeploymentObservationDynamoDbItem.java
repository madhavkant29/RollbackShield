package com.rollbackshield.integrations.adapter;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Single-table item for a DeploymentObservation. gsi1 is keyed by service
 * and sorted by observedAt, so "latest for service" is one descending query.
 */
@DynamoDbBean
public class DeploymentObservationDynamoDbItem {

    private String pk;
    private String sk;
    private String gsi1pk;
    private String gsi1sk;
    private String observationId;
    private String organizationId;
    private String serviceId;
    private String integrationId;
    private String runtimeName;
    private String runtimeExternalId;
    private String candidateRevision;
    private String previousRevision;
    private String candidateArtifactDigest;
    private String previousArtifactDigest;
    private String commitSha;
    private String branch;
    private String artifactRepository;
    private String deploymentStatus;
    private String releaseId;
    private String observedAt;

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

    public String getObservationId() { return observationId; }
    public void setObservationId(String observationId) { this.observationId = observationId; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }

    public String getRuntimeName() { return runtimeName; }
    public void setRuntimeName(String runtimeName) { this.runtimeName = runtimeName; }

    public String getRuntimeExternalId() { return runtimeExternalId; }
    public void setRuntimeExternalId(String runtimeExternalId) { this.runtimeExternalId = runtimeExternalId; }

    public String getCandidateRevision() { return candidateRevision; }
    public void setCandidateRevision(String candidateRevision) { this.candidateRevision = candidateRevision; }

    public String getPreviousRevision() { return previousRevision; }
    public void setPreviousRevision(String previousRevision) { this.previousRevision = previousRevision; }

    public String getCandidateArtifactDigest() { return candidateArtifactDigest; }
    public void setCandidateArtifactDigest(String value) { this.candidateArtifactDigest = value; }

    public String getPreviousArtifactDigest() { return previousArtifactDigest; }
    public void setPreviousArtifactDigest(String value) { this.previousArtifactDigest = value; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getArtifactRepository() { return artifactRepository; }
    public void setArtifactRepository(String artifactRepository) { this.artifactRepository = artifactRepository; }

    public String getDeploymentStatus() { return deploymentStatus; }
    public void setDeploymentStatus(String deploymentStatus) { this.deploymentStatus = deploymentStatus; }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public String getObservedAt() { return observedAt; }
    public void setObservedAt(String observedAt) { this.observedAt = observedAt; }
}
