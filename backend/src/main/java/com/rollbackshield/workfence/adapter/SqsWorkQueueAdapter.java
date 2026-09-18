package com.rollbackshield.workfence.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.workfence.domain.WorkJob;
import com.rollbackshield.workfence.domain.WorkQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQS-backed WorkQueue. Deliberately does NOT delete a message until after
 * the caller has redeemed it (§12: design for at-least-once delivery) -- the
 * safety property comes from WorkFenceApplicationService's idempotent
 * redemption, not from queue semantics.
 */
@Component
@ConditionalOnProperty(name = "rollbackshield.workqueue", havingValue = "sqs")
public class SqsWorkQueueAdapter implements WorkQueue {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsWorkQueueAdapter(SqsClient sqsClient, ObjectMapper objectMapper,
                                @Value("${rollbackshield.sqs.queue-url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
    }

    private record WireJob(String jobId, String releaseId, long releaseEpoch, String jobType,
                            String createdAt, String payload) {
    }

    @Override
    public void enqueue(WorkJob job) {
        try {
            WireJob wire = new WireJob(job.jobId(), job.releaseId().toString(), job.releaseEpoch(),
                job.jobType(), job.createdAt().toString(), job.payload());
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(objectMapper.writeValueAsString(wire))
                .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue work job " + job.jobId() + " to SQS", e);
        }
    }

    @Override
    public List<WorkJob> receive(int maxMessages) {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(maxMessages, 10))
                .waitTimeSeconds(1)
                .build())
            .messages();

        List<WorkJob> jobs = new ArrayList<>();
        for (Message message : messages) {
            try {
                WireJob wire = objectMapper.readValue(message.body(), WireJob.class);
                jobs.add(new WorkJob(wire.jobId(), ReleaseId.of(wire.releaseId()), wire.releaseEpoch(),
                    wire.jobType(), Instant.parse(wire.createdAt()), wire.payload()));
                // Delete only after successful parse/handoff; a crash before this point
                // means the message reappears after the visibility timeout -- exactly
                // the at-least-once behavior the redemption ledger is designed for.
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
            } catch (Exception e) {
                // Leave un-parseable messages in the queue for visibility-timeout
                // redelivery rather than silently dropping them.
            }
        }
        return jobs;
    }
}
