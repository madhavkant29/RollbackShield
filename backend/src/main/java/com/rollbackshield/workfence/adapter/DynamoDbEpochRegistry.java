package com.rollbackshield.workfence.adapter;

import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.workfence.domain.EpochRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;

/**
 * DynamoDB-backed {@link EpochRegistry}: invalidation survives restarts and
 * is visible to every backend task, which is what makes the work fence safe
 * beyond a single instance (ADR-005).
 */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbEpochRegistry implements EpochRegistry {

    private final DynamoDbTable<EpochInvalidationDynamoDbItem> table;

    public DynamoDbEpochRegistry(DynamoDbEnhancedClient enhancedClient,
                                  @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(EpochInvalidationDynamoDbItem.class));
    }

    @Override
    public boolean isValid(ReleaseId releaseId, long epoch) {
        return table.getItem(key(releaseId, epoch)) == null;
    }

    @Override
    public void invalidate(ReleaseId releaseId, long epoch) {
        EpochInvalidationDynamoDbItem item = new EpochInvalidationDynamoDbItem();
        String pk = "RELEASE#" + releaseId;
        item.setPk(pk);
        item.setSk("EPOCH#" + epoch);
        item.setReleaseId(releaseId.toString());
        item.setEpoch(epoch);
        item.setInvalidatedAt(Instant.now().toString());
        table.putItem(item); // idempotent: invalidating twice is a no-op overwrite
    }

    private static Key key(ReleaseId releaseId, long epoch) {
        return Key.builder()
            .partitionValue("RELEASE#" + releaseId)
            .sortValue("EPOCH#" + epoch)
            .build();
    }
}
