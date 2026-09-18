package com.rollbackshield.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.adapter.DynamoDbAuditTrail;
import com.rollbackshield.catalog.adapter.DynamoDbAppServiceRepository;
import com.rollbackshield.catalog.adapter.DynamoDbOrganizationRepository;
import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.contract.adapter.DynamoDbRollbackContractRepository;
import com.rollbackshield.contract.domain.CompatibilityRule;
import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.release.adapter.DynamoDbReleaseRepository;
import com.rollbackshield.release.adapter.ReleaseDynamoDbItem;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.release.domain.ReleaseState;
import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.PolicyVersion;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.workfence.adapter.DynamoDbEpochRegistry;
import com.rollbackshield.workfence.adapter.DynamoDbRedemptionLedger;
import com.rollbackshield.workfence.domain.RedeemOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real DynamoDB adapters against DynamoDB Local (§L: the
 * Enhanced-Client {@code *Item} classes were written from training
 * knowledge and never met a real DynamoDB). The table is created with the
 * exact key schema and gsi1 from `infrastructure/lib/data-stack.ts`, so a
 * key-design mismatch between an item class and the CDK table fails here
 * rather than in AWS.
 *
 * Skipped automatically when Docker isn't available.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbAdapterIntegrationTest {

    private static final String TABLE = "rollbackshield";

    @Container
    static final GenericContainer<?> DYNAMODB =
        new GenericContainer<>("amazon/dynamodb-local:2.5.2").withExposedPorts(8000);

    private static DynamoDbEnhancedClient enhancedClient;
    private static DynamoDbTable<ReleaseDynamoDbItem> rawReleaseTable;

    private static DynamoDbReleaseRepository releaseRepo;
    private static DynamoDbRollbackContractRepository contractRepo;
    private static DynamoDbAppServiceRepository serviceRepo;
    private static DynamoDbOrganizationRepository organizationRepo;
    private static DynamoDbAuditTrail auditTrail;
    private static DynamoDbEpochRegistry epochRegistry;
    private static DynamoDbRedemptionLedger redemptionLedger;

    @BeforeAll
    static void setUp() {
        DynamoDbClient client = DynamoDbClient.builder()
            .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000)))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build();

        client.createTable(CreateTableRequest.builder()
            .tableName(TABLE)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("gsi1sk").attributeType(ScalarAttributeType.S).build())
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName("gsi1")
                .keySchema(
                    KeySchemaElement.builder().attributeName("gsi1pk").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("gsi1sk").keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build())
            .build());

        enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
        rawReleaseTable = enhancedClient.table(TABLE, TableSchema.fromBean(ReleaseDynamoDbItem.class));

        releaseRepo = new DynamoDbReleaseRepository(enhancedClient, TABLE);
        contractRepo = new DynamoDbRollbackContractRepository(enhancedClient, TABLE, new ObjectMapper());
        serviceRepo = new DynamoDbAppServiceRepository(enhancedClient, TABLE);
        organizationRepo = new DynamoDbOrganizationRepository(enhancedClient, TABLE);
        auditTrail = new DynamoDbAuditTrail(enhancedClient, TABLE);
        epochRegistry = new DynamoDbEpochRegistry(enhancedClient, TABLE);
        redemptionLedger = new DynamoDbRedemptionLedger(enhancedClient, TABLE);
    }

    @Test
    void releaseRoundTripsAndAppearsOnServiceIndex() {
        OrganizationId org = OrganizationId.newId();
        ServiceId service = ServiceId.newId();
        Release release = Release.draft(org, service, "v1", "v2");
        releaseRepo.save(release);

        Optional<Release> loaded = releaseRepo.findById(release.id());
        assertThat(loaded).contains(release);

        assertThat(releaseRepo.findByService(service))
            .extracting(Release::id)
            .containsExactly(release.id());
    }

    /**
     * This is the one HANDOFF.md flagged as "looks right on paper, needs a
     * real DynamoDB to confirm": compareAndSave copies current.getVersion()
     * onto the next item and relies on @DynamoDbVersionAttribute to produce
     * a conditional write. If the enhanced client isn't actually applying
     * the version extension, this test fails.
     */
    @Test
    void compareAndSavePerformsAnOptimisticConditionalWrite() {
        OrganizationId org = OrganizationId.newId();
        ServiceId service = ServiceId.newId();
        Release release = Release.draft(org, service, "v1", "v2");
        releaseRepo.save(release);

        String key = "RELEASE#" + release.id();
        ReleaseDynamoDbItem created = rawReleaseTable.getItem(
            Key.builder().partitionValue(key).sortValue(key).build());
        assertThat(created.getVersion())
            .as("version attribute must be populated by the enhanced client on first write")
            .isNotNull();

        // correct expected state -> accepted
        Release preparing = release.transitionTo(ReleaseState.PREPARING);
        assertThat(releaseRepo.compareAndSave(preparing, ReleaseState.DRAFT)).isTrue();

        ReleaseDynamoDbItem afterFirst = rawReleaseTable.getItem(
            Key.builder().partitionValue(key).sortValue(key).build());
        assertThat(afterFirst.getVersion())
            .as("a successful compareAndSave must advance the version")
            .isGreaterThan(created.getVersion());

        // wrong expected state -> rejected, no write
        assertThat(releaseRepo.compareAndSave(release.transitionTo(ReleaseState.PREPARING), ReleaseState.READY))
            .isFalse();

        // a stale-version write must be rejected by the conditional put
        ReleaseDynamoDbItem staleCopy = rawReleaseTable.getItem(
            Key.builder().partitionValue(key).sortValue(key).build());
        staleCopy.setVersion(created.getVersion());
        assertThatThrownBy(() -> rawReleaseTable.putItem(staleCopy))
            .isInstanceOf(ConditionalCheckFailedException.class);
    }

    @Test
    void contractRulesSurviveTheJsonRoundTripAndIndexLookups() {
        OrganizationId org = OrganizationId.newId();
        ServiceId service = ServiceId.newId();
        ReleaseId releaseId = ReleaseId.newId();

        List<CompatibilityRule> rules = List.of(
            new CompatibilityRule.EnumAllowedValues("Order", "status", Set.of("CREATED", "PAID")),
            new CompatibilityRule.Nullability("Order", "refundId", true),
            new CompatibilityRule.NumericRange("Order", "amount", 0.0, 100000.0),
            new CompatibilityRule.RequiredField("Order", "customerId"),
            new CompatibilityRule.ForbiddenValue("Order", "status", Set.of("PARTIALLY_REFUNDED")));

        ContractId contractId = ContractId.newId();
        RollbackContract contract = new RollbackContract(
            contractId, org, service, releaseId, 1, new PolicyVersion(1),
            Instant.now(), Instant.now(), Duration.ofSeconds(7200), rules,
            true, RollbackContract.ContractStatus.ACTIVE, "hash-abc");

        contractRepo.save(contract);

        // direct lookup goes through gsi1; must return the identical rule set
        RollbackContract viaId = contractRepo.findById(contractId).orElseThrow();
        assertThat(viaId).isEqualTo(contract);
        assertThat(viaId.rules()).containsExactlyElementsOf(rules);

        // release-scoped lookup goes through the base table partition
        RollbackContract active = contractRepo.findActiveForRelease(releaseId).orElseThrow();
        assertThat(active.contractId()).isEqualTo(contractId);
    }

    @Test
    void activeContractLookupIgnoresNonActiveContractsOnTheSameRelease() {
        OrganizationId org = OrganizationId.newId();
        ServiceId service = ServiceId.newId();
        ReleaseId releaseId = ReleaseId.newId();

        RollbackContract revoked = new RollbackContract(
            ContractId.newId(), org, service, releaseId, 1, new PolicyVersion(1),
            Instant.now(), Instant.now(), Duration.ofSeconds(7200),
            List.of(new CompatibilityRule.RequiredField("Order", "customerId")),
            false, RollbackContract.ContractStatus.REVOKED, "hash-revoked");
        RollbackContract active = new RollbackContract(
            ContractId.newId(), org, service, releaseId, 2, new PolicyVersion(2),
            Instant.now(), Instant.now(), Duration.ofSeconds(7200),
            List.of(new CompatibilityRule.RequiredField("Order", "customerId")),
            false, RollbackContract.ContractStatus.ACTIVE, "hash-active");
        contractRepo.save(revoked);
        contractRepo.save(active);

        assertThat(contractRepo.findActiveForRelease(releaseId))
            .map(RollbackContract::contractId)
            .contains(active.contractId());
    }

    @Test
    void organizationAndServiceRoundTripThroughTheOrgIndex() {
        OrganizationId org = OrganizationId.newId();
        Organization organization = new Organization(org, "Org A", Instant.now());
        organizationRepo.save(organization);
        assertThat(organizationRepo.findById(org)).contains(organization);

        AppService service = new AppService(ServiceId.newId(), org, "checkout", Instant.now());
        serviceRepo.save(service);

        assertThat(serviceRepo.findById(service.id())).contains(service);
        assertThat(serviceRepo.findByOrganization(org))
            .extracting(AppService::id)
            .containsExactly(service.id());
    }

    @Test
    void auditTrailAppendsAndReadsBackChronologicallyWithMetadata() {
        OrganizationId org = OrganizationId.newId();
        ReleaseId releaseId = ReleaseId.newId();

        AuditEvent first = new AuditEvent("e1", org.toString(), releaseId.toString(), "user",
            AuditAction.RELEASE_CREATED, releaseId.toString(), Instant.ofEpochMilli(1_000), "r1",
            "created", null, "DRAFT", Map.of("k", "v"));
        AuditEvent second = new AuditEvent("e2", org.toString(), releaseId.toString(), "user",
            AuditAction.RELEASE_STATE_CHANGED, releaseId.toString(), Instant.ofEpochMilli(2_000), "r2",
            "preparing", "DRAFT", "PREPARING", Map.of());

        auditTrail.append(second);
        auditTrail.append(first);

        assertThat(auditTrail.listForRelease(releaseId.toString()))
            .extracting(AuditEvent::eventId)
            .containsExactly("e1", "e2");
        assertThat(auditTrail.listForRelease(releaseId.toString()).get(0).metadata())
            .containsEntry("k", "v");
    }

    @Test
    void epochInvalidationIsDurableAndIdempotent() {
        ReleaseId releaseId = ReleaseId.newId();
        assertThat(epochRegistry.isValid(releaseId, 1)).isTrue();

        epochRegistry.invalidate(releaseId, 1);
        assertThat(epochRegistry.isValid(releaseId, 1)).isFalse();

        epochRegistry.invalidate(releaseId, 1); // idempotent
        assertThat(epochRegistry.isValid(releaseId, 1)).isFalse();

        // epoch scoping is per release
        assertThat(epochRegistry.isValid(ReleaseId.newId(), 1)).isTrue();
    }

    @Test
    void redemptionLedgerKeepsTheFirstCommittedOutcome() {
        String jobId = UUID.randomUUID().toString();
        ReleaseId releaseId = ReleaseId.newId();

        assertThat(redemptionLedger.commitIfAbsent(jobId, releaseId, RedeemOutcome.EXECUTE))
            .isEqualTo(RedeemOutcome.EXECUTE);
        assertThat(redemptionLedger.commitIfAbsent(jobId, releaseId, RedeemOutcome.CANCEL))
            .isEqualTo(RedeemOutcome.EXECUTE);
    }

    /**
     * The multi-task guarantee ADR-005 depends on: concurrent redemptions
     * of the same jobId must all observe one committed outcome, even when
     * they propose different candidates. The conditional put decides it.
     */
    @Test
    void concurrentRedemptionsCommitExactlyOneOutcome() throws Exception {
        String jobId = UUID.randomUUID().toString();
        ReleaseId releaseId = ReleaseId.newId();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedeemOutcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                RedeemOutcome candidate = (i % 2 == 0) ? RedeemOutcome.EXECUTE : RedeemOutcome.CANCEL;
                futures.add(pool.submit(() -> {
                    start.await();
                    return redemptionLedger.commitIfAbsent(jobId, releaseId, candidate);
                }));
            }
            start.countDown();

            Set<RedeemOutcome> observed = new HashSet<>();
            for (Future<RedeemOutcome> future : futures) {
                observed.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(observed)
                .as("every concurrent writer must observe the same committed outcome")
                .hasSize(1);
            assertThat(redemptionLedger.commitIfAbsent(jobId, releaseId, RedeemOutcome.CANCEL))
                .isEqualTo(observed.iterator().next());
        } finally {
            pool.shutdownNow();
        }
    }
}
