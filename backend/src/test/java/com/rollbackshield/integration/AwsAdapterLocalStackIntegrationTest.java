package com.rollbackshield.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.events.adapter.EventBridgeEventPublisher;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.workfence.adapter.SqsWorkQueueAdapter;
import com.rollbackshield.workfence.domain.WorkJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the EventBridge and SQS adapters against LocalStack. These were the
 * last "instantiated but never executed" files in the backend: the SQS wire
 * format for {@link WorkJob} and the EventBridge publish path are now
 * exercised for real, including an EventBridge rule delivering to an SQS
 * sink to prove the event actually lands on the bus.
 *
 * Skips automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsAdapterLocalStackIntegrationTest {

    private static final String REGION = "us-east-1";

    /** Mirrors Spring Boot's ObjectMapper, which registers the JavaTime module. */
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Container
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:3.7.2")
        .withExposedPorts(4566)
        .withEnv("SERVICES", "sqs,events")
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));

    private static URI endpoint;
    private static SqsClient sqs;
    private static EventBridgeClient eventBridge;

    @BeforeAll
    static void setUp() {
        endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
            .credentialsProvider(credentials).build();
        eventBridge = EventBridgeClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
            .credentialsProvider(credentials).build();
    }

    @Test
    void sqsWorkQueueRoundTripsAJobOverTheWire() {
        String queueUrl = sqs.createQueue(b -> b.queueName("rollbackshield-work-it")).queueUrl();
        var adapter = new SqsWorkQueueAdapter(sqs, MAPPER, queueUrl);

        WorkJob job = new WorkJob("job-1", ReleaseId.newId(), 7, "ISSUE_PARTIAL_REFUND_WEBHOOK",
            Instant.parse("2026-01-02T03:04:05Z"), "order-1");
        adapter.enqueue(job);

        List<WorkJob> received = adapter.receive(10);

        assertThat(received)
            .as("enqueue/receive must round-trip every field through the JSON wire format")
            .containsExactly(job);
        assertThat(adapter.receive(10))
            .as("a successfully handed-off message is deleted, not redelivered immediately")
            .isEmpty();
    }

    @Test
    void eventBridgePublishesEventsThatReachARuleTarget() {
        String busName = "rollbackshield-events-it";
        eventBridge.createEventBus(b -> b.name(busName));

        String sinkUrl = sqs.createQueue(b -> b.queueName("event-sink-it")).queueUrl();
        String sinkArn = sqs.getQueueAttributes(b -> b.queueUrl(sinkUrl)
            .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);

        eventBridge.putRule(b -> b.eventBusName(busName).name("all-control-plane-events")
            .eventPattern("{\"source\":[\"rollbackshield.control-plane\"]}"));
        eventBridge.putTargets(b -> b.eventBusName(busName).rule("all-control-plane-events")
            .targets(Target.builder().id("sink").arn(sinkArn).build()));

        new EventBridgeEventPublisher(eventBridge, MAPPER, busName)
            .publish(DomainEvent.of("ReleaseCreated", "org-1", "release-1", Map.of("candidateVersionLabel", "v2")));

        String delivered = awaitMessage(sinkUrl, Duration.ofSeconds(20));
        assertThat(delivered)
            .as("the event must be delivered to the rule's SQS target")
            .contains("ReleaseCreated")
            .contains("rollbackshield.control-plane");
    }

    private static String awaitMessage(String queueUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(2).build())
                .messages();
            if (!messages.isEmpty()) {
                return messages.get(0).body();
            }
        }
        return "";
    }
}
