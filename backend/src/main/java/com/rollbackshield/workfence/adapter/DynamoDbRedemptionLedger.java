package com.rollbackshield.workfence.adapter;

import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import com.rollbackshield.workfence.domain.RedemptionLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;

/**
 * DynamoDB-backed {@link RedemptionLedger}. The first committed outcome for
 * a jobId wins via a conditional put (`attribute_not_exists(pk)`); a losing
 * concurrent writer re-reads and returns the winner's outcome. This is what
 * makes the fence correct across tasks, not just threads (ADR-005).
 */
@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbRedemptionLedger implements RedemptionLedger {

    private final DynamoDbTable<RedemptionDynamoDbItem> table;

    public DynamoDbRedemptionLedger(DynamoDbEnhancedClient enhancedClient,
                                     @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(RedemptionDynamoDbItem.class));
    }

    @Override
    public RedeemOutcome commitIfAbsent(String jobId, ReleaseId releaseId, RedeemOutcome candidate) {
        Key key = key(jobId);

        RedemptionDynamoDbItem existing = table.getItem(key);
        if (existing != null) {
            return RedeemOutcome.valueOf(existing.getOutcome());
        }

        RedemptionDynamoDbItem item = new RedemptionDynamoDbItem();
        item.setPk("JOB#" + jobId);
        item.setSk("JOB#" + jobId);
        item.setJobId(jobId);
        item.setReleaseId(releaseId.toString());
        item.setOutcome(candidate.name());
        item.setCommittedAt(Instant.now().toString());

        try {
            table.putItem(PutItemEnhancedRequest.builder(RedemptionDynamoDbItem.class)
                .item(item)
                .conditionExpression(Expression.builder()
                    .expression("attribute_not_exists(pk)")
                    .build())
                .build());
            return candidate;
        } catch (ConditionalCheckFailedException e) {
            // Another writer committed first; its outcome is the one that counts.
            RedemptionDynamoDbItem committed = table.getItem(key);
            return committed != null ? RedeemOutcome.valueOf(committed.getOutcome()) : candidate;
        }
    }

    private static Key key(String jobId) {
        return Key.builder().partitionValue("JOB#" + jobId).sortValue("JOB#" + jobId).build();
    }
}
