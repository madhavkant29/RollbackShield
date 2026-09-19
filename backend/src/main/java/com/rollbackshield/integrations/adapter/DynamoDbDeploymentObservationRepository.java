package com.rollbackshield.integrations.adapter;

import com.rollbackshield.integrations.domain.DeploymentIdentity;
import com.rollbackshield.integrations.domain.DeploymentObservation;
import com.rollbackshield.integrations.domain.DeploymentObservationRepository;
import com.rollbackshield.shared.domain.DeploymentObservationId;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** DynamoDB adapter for DeploymentObservationRepository. */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbDeploymentObservationRepository implements DeploymentObservationRepository {

    private final DynamoDbTable<DeploymentObservationDynamoDbItem> table;

    public DynamoDbDeploymentObservationRepository(DynamoDbEnhancedClient enhancedClient,
                                                   @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName,
            TableSchema.fromBean(DeploymentObservationDynamoDbItem.class));
    }

    @Override
    public DeploymentObservation save(DeploymentObservation observation) {
        table.putItem(toItem(observation));
        return observation;
    }

    @Override
    public Optional<DeploymentObservation> findById(DeploymentObservationId id) {
        String key = "OBSERVATION#" + id;
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(key).sortValue(key).build()))
            .map(DynamoDbDeploymentObservationRepository::toDomain);
    }

    @Override
    public List<DeploymentObservation> findByService(ServiceId serviceId) {
        return table.index("gsi1")
            // SERVICE# partition is shared with Release items; prefix-filter
            // so releases are never mapped as observations (runtimeName NPE).
            .query(QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortBeginsWith(Key.builder()
                    .partitionValue("SERVICE#" + serviceId).sortValue("OBSERVATION#").build()))
                .scanIndexForward(false)
                .build())
            .stream()
            .flatMap(page -> page.items().stream())
            .map(DynamoDbDeploymentObservationRepository::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<DeploymentObservation> findLatestForService(ServiceId serviceId) {
        return table.index("gsi1")
            .query(QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortBeginsWith(Key.builder()
                    .partitionValue("SERVICE#" + serviceId).sortValue("OBSERVATION#").build()))
                .scanIndexForward(false)
                .limit(1)
                .build())
            .stream()
            .findFirst()
            .flatMap(page -> page.items().stream().findFirst())
            .map(DynamoDbDeploymentObservationRepository::toDomain);
    }

    static DeploymentObservationDynamoDbItem toItem(DeploymentObservation observation) {
        DeploymentIdentity identity = observation.identity();
        DeploymentObservationDynamoDbItem item = new DeploymentObservationDynamoDbItem();
        String key = "OBSERVATION#" + observation.id();
        item.setPk(key);
        item.setSk(key);
        item.setGsi1pk("SERVICE#" + observation.serviceId());
        item.setGsi1sk("OBSERVATION#" + observation.observedAt() + "#" + observation.id());
        item.setObservationId(observation.id().toString());
        item.setOrganizationId(observation.organizationId().toString());
        item.setServiceId(observation.serviceId().toString());
        item.setIntegrationId(observation.integrationId().toString());
        item.setRuntimeName(identity.runtimeName());
        item.setRuntimeExternalId(identity.runtimeExternalId());
        item.setCandidateRevision(identity.candidateRevision());
        item.setPreviousRevision(identity.previousRevision());
        item.setCandidateArtifactDigest(identity.candidateArtifactDigest());
        item.setPreviousArtifactDigest(identity.previousArtifactDigest());
        item.setCommitSha(identity.commitSha());
        item.setBranch(identity.branch());
        item.setArtifactRepository(identity.artifactRepository());
        item.setDeploymentStatus(identity.deploymentStatus());
        item.setReleaseId(observation.releaseId() == null ? null : observation.releaseId().toString());
        item.setObservedAt(observation.observedAt().toString());
        return item;
    }

    static DeploymentObservation toDomain(DeploymentObservationDynamoDbItem item) {
        DeploymentIdentity identity = new DeploymentIdentity(
            item.getRuntimeName(),
            item.getRuntimeExternalId(),
            item.getCandidateRevision(),
            item.getPreviousRevision(),
            item.getCandidateArtifactDigest(),
            item.getPreviousArtifactDigest(),
            item.getCommitSha(),
            item.getBranch(),
            item.getArtifactRepository(),
            item.getDeploymentStatus());
        return new DeploymentObservation(
            DeploymentObservationId.of(item.getObservationId()),
            OrganizationId.of(item.getOrganizationId()),
            ServiceId.of(item.getServiceId()),
            IntegrationId.of(item.getIntegrationId()),
            identity,
            item.getReleaseId() == null ? null : ReleaseId.of(item.getReleaseId()),
            Instant.parse(item.getObservedAt()));
    }
}
