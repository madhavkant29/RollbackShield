package com.rollbackshield.workfence.adapter;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Durable record of an invalidated epoch. Shares its partition with the
 * release (pk = RELEASE#id) so all release-scoped records stay together;
 * sk = EPOCH#&lt;epoch&gt; makes invalidation a single idempotent PutItem and
 * validity a single GetItem.
 */
@DynamoDbBean
public class EpochInvalidationDynamoDbItem {

    private String pk;       // "RELEASE#<releaseId>"
    private String sk;       // "EPOCH#<epoch>"
    private String releaseId;
    private long epoch;
    private String invalidatedAt;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }

    public String getInvalidatedAt() { return invalidatedAt; }
    public void setInvalidatedAt(String invalidatedAt) { this.invalidatedAt = invalidatedAt; }
}
