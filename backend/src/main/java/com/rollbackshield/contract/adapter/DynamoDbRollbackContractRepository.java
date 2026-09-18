package com.rollbackshield.contract.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rollbackshield.contract.api.ContractDtos.RuleDto;
import com.rollbackshield.contract.application.ContractApplicationService;
import com.rollbackshield.contract.domain.CompatibilityRule;
import com.rollbackshield.contract.domain.RollbackContract;
import com.rollbackshield.contract.domain.RollbackContractRepository;
import com.rollbackshield.shared.domain.ContractId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.PolicyVersion;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbRollbackContractRepository implements RollbackContractRepository {

    private final DynamoDbTable<RollbackContractDynamoDbItem> table;
    private final ObjectMapper objectMapper;

    public DynamoDbRollbackContractRepository(DynamoDbEnhancedClient enhancedClient,
                                               @Value("${rollbackshield.dynamodb.table-name}") String tableName,
                                               ObjectMapper objectMapper) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(RollbackContractDynamoDbItem.class));
        this.objectMapper = objectMapper;
    }

    @Override
    public RollbackContract save(RollbackContract contract) {
        table.putItem(toItem(contract));
        return contract;
    }

    @Override
    public Optional<RollbackContract> findById(ContractId id) {
        return table.index("gsi1")
            .query(QueryConditional.keyEqualTo(Key.builder().partitionValue("CONTRACT#" + id).build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .findFirst()
            .map(this::toDomain);
    }

    @Override
    public Optional<RollbackContract> findActiveForRelease(ReleaseId releaseId) {
        String pk = "RELEASE#" + releaseId;
        return table.query(QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(pk).sortValue("CONTRACT#").build()))
            .items().stream()
            .map(this::toDomain)
            .filter(c -> c.status() == RollbackContract.ContractStatus.ACTIVE)
            .findFirst();
    }

    private RollbackContractDynamoDbItem toItem(RollbackContract contract) {
        try {
            List<RuleDto> ruleDtos = contract.rules().stream().map(ContractApplicationService::toDto).toList();
            RollbackContractDynamoDbItem item = new RollbackContractDynamoDbItem();
            item.setPk("RELEASE#" + contract.releaseId());
            item.setSk("CONTRACT#" + contract.contractId());
            item.setGsi1pk("CONTRACT#" + contract.contractId());
            item.setGsi1sk("CONTRACT#" + contract.contractId());
            item.setContractId(contract.contractId().toString());
            item.setOrganizationId(contract.organizationId().toString());
            item.setServiceId(contract.serviceId().toString());
            item.setReleaseId(contract.releaseId().toString());
            item.setContractVersion(contract.contractVersion());
            item.setPolicyVersion(contract.policyVersion().value());
            item.setCreatedAt(contract.createdAt().toString());
            item.setActivatedAt(contract.activatedAt() != null ? contract.activatedAt().toString() : null);
            item.setRollbackWindowSeconds(contract.rollbackWindow().toSeconds());
            item.setRulesJson(objectMapper.writeValueAsString(ruleDtos));
            item.setCandidateEpochRequiredForAsyncWork(contract.candidateEpochRequiredForAsyncWork());
            item.setStatus(contract.status().name());
            item.setContentHash(contract.contentHash());
            return item;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize contract rules", e);
        }
    }

    private RollbackContract toDomain(RollbackContractDynamoDbItem item) {
        try {
            List<RuleDto> ruleDtos = objectMapper.readValue(item.getRulesJson(), new TypeReference<List<RuleDto>>() {});
            List<CompatibilityRule> rules = ruleDtos.stream()
                .map(ContractApplicationService::toDomainRule).toList();

            return new RollbackContract(
                ContractId.of(item.getContractId()),
                OrganizationId.of(item.getOrganizationId()),
                ServiceId.of(item.getServiceId()),
                ReleaseId.of(item.getReleaseId()),
                item.getContractVersion(),
                new PolicyVersion(item.getPolicyVersion()),
                Instant.parse(item.getCreatedAt()),
                item.getActivatedAt() != null ? Instant.parse(item.getActivatedAt()) : null,
                Duration.ofSeconds(item.getRollbackWindowSeconds()),
                rules,
                item.isCandidateEpochRequiredForAsyncWork(),
                RollbackContract.ContractStatus.valueOf(item.getStatus()),
                item.getContentHash());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize contract rules", e);
        }
    }
}
