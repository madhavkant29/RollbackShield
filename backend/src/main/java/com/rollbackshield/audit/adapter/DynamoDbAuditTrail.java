package com.rollbackshield.audit.adapter;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
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
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbAuditTrail implements AuditTrail {

    private final DynamoDbTable<AuditEventDynamoDbItem> table;

    public DynamoDbAuditTrail(DynamoDbEnhancedClient enhancedClient,
                               @Value("${rollbackshield.dynamodb.table-name}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(AuditEventDynamoDbItem.class));
    }

    @Override
    public void append(AuditEvent event) {
        // No update/delete path exists on this class -- append-only by construction (§31).
        table.putItem(toItem(event));
    }

    @Override
    public List<AuditEvent> listForRelease(String releaseId) {
        String pk = "RELEASE#" + releaseId;
        return table.query(QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(pk).sortValue("AUDIT#").build()))
            .items().stream()
            .map(DynamoDbAuditTrail::toDomain)
            .collect(Collectors.toList());
    }

    private static AuditEventDynamoDbItem toItem(AuditEvent event) {
        AuditEventDynamoDbItem item = new AuditEventDynamoDbItem();
        String pk = "RELEASE#" + event.releaseId();
        item.setPk(pk);
        item.setSk("AUDIT#" + event.timestamp().toEpochMilli() + "#" + event.eventId());
        item.setEventId(event.eventId());
        item.setOrganizationId(event.organizationId());
        item.setReleaseId(event.releaseId());
        item.setActor(event.actor());
        item.setAction(event.action().name());
        item.setTarget(event.target());
        item.setTimestamp(event.timestamp().toString());
        item.setRequestId(event.requestId());
        item.setReason(event.reason());
        item.setPreviousState(event.previousState());
        item.setNewState(event.newState());
        item.setMetadata(event.metadata());
        return item;
    }

    private static AuditEvent toDomain(AuditEventDynamoDbItem item) {
        return new AuditEvent(item.getEventId(), item.getOrganizationId(), item.getReleaseId(), item.getActor(),
            AuditAction.valueOf(item.getAction()), item.getTarget(), Instant.parse(item.getTimestamp()),
            item.getRequestId(), item.getReason(), item.getPreviousState(), item.getNewState(),
            item.getMetadata());
    }
}
