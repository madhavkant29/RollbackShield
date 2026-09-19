package com.rollbackshield.release.adapter;

import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseRepository;
import com.rollbackshield.release.domain.ReleaseState;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DynamoDB adapter for ReleaseRepository, implementing the exact same port
 * InMemoryReleaseRepository does. Single-table design: PK/SK = RELEASE#id
 * for direct lookup; gsi1 (SERVICE#serviceId) for service-scoped listing
 * without a Scan (§34).
 */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbReleaseRepository implements ReleaseRepository {

    private final DynamoDbTable<ReleaseDynamoDbItem> table;

    public DynamoDbReleaseRepository(DynamoDbEnhancedClient enhancedClient,
                                      @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(ReleaseDynamoDbItem.class));
    }

    @Override
    public Release save(Release release) {
        table.putItem(toItem(release));
        return release;
    }

    @Override
    public Optional<Release> findById(ReleaseId id) {
        String key = "RELEASE#" + id;
        ReleaseDynamoDbItem item = table.getItem(Key.builder().partitionValue(key).sortValue(key).build());
        return Optional.ofNullable(item).map(DynamoDbReleaseRepository::toDomain);
    }

    @Override
    public List<Release> findByService(ServiceId serviceId) {
        return table.index("gsi1")
            // SERVICE# partition also carries DeploymentObservation items;
            // prefix-filter so observations are never mapped as releases.
            .query(QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue("SERVICE#" + serviceId).sortValue("RELEASE#").build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .map(DynamoDbReleaseRepository::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public boolean compareAndSave(Release release, ReleaseState expectedState) {
        String key = "RELEASE#" + release.id();
        ReleaseDynamoDbItem current = table.getItem(Key.builder().partitionValue(key).sortValue(key).build());
        if (current == null || !current.getState().equals(expectedState.name())) {
            return false;
        }
        ReleaseDynamoDbItem next = toItem(release);
        next.setVersion(current.getVersion()); // enhanced client CAS's on this automatically
        try {
            table.putItem(next);
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Someone else wrote to this release between our read and this write.
            return false;
        }
    }

    private static ReleaseDynamoDbItem toItem(Release release) {
        ReleaseDynamoDbItem item = new ReleaseDynamoDbItem();
        String key = "RELEASE#" + release.id();
        item.setPk(key);
        item.setSk(key);
        item.setGsi1pk("SERVICE#" + release.serviceId());
        item.setGsi1sk(key);
        item.setReleaseId(release.id().toString());
        item.setOrganizationId(release.organizationId().toString());
        item.setServiceId(release.serviceId().toString());
        item.setPreviousVersionLabel(release.previousVersionLabel());
        item.setCandidateVersionLabel(release.candidateVersionLabel());
        item.setState(release.state().name());
        item.setEpoch(release.epoch());
        item.setCreatedAt(release.createdAt().toString());
        item.setUpdatedAt(release.updatedAt().toString());
        return item;
    }

    private static Release toDomain(ReleaseDynamoDbItem item) {
        return new Release(
            ReleaseId.of(item.getReleaseId()),
            OrganizationId.of(item.getOrganizationId()),
            ServiceId.of(item.getServiceId()),
            item.getPreviousVersionLabel(),
            item.getCandidateVersionLabel(),
            ReleaseState.valueOf(item.getState()),
            item.getEpoch(),
            Instant.parse(item.getCreatedAt()),
            Instant.parse(item.getUpdatedAt())
        );
    }
}
