package com.rollbackshield.workfence.adapter;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Durable committed outcome for one jobId. jobId is a globally unique UUID,
 * so pk = sk = JOB#&lt;jobId&gt; makes redemption a single point read and the
 * first commit a conditional PutItem (`attribute_not_exists(pk)`).
 */
@DynamoDbBean
public class RedemptionDynamoDbItem {

    private String pk;          // "JOB#<jobId>"
    private String sk;          // "JOB#<jobId>"
    private String jobId;
    private String releaseId;
    private String outcome;     // RedeemOutcome.name()
    private String committedAt;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getCommittedAt() { return committedAt; }
    public void setCommittedAt(String committedAt) { this.committedAt = committedAt; }
}
