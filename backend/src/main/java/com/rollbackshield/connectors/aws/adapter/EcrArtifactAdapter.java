package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorCapability;
import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceType;
import com.rollbackshield.integrations.domain.connector.ArtifactDescriptor;
import com.rollbackshield.integrations.domain.connector.ArtifactVerificationPort;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ecr.model.ImageDetail;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;
import software.amazon.awssdk.services.ecr.model.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ECR: artifact identity by immutable digest. Existence of a rollback
 * artifact is decided by DescribeImages on the digest -- a tag alone is
 * never treated as proof, because tags are mutable.
 */
@Component
public class EcrArtifactAdapter implements CapabilityProvider, DiscoveryContributionPort,
    ArtifactVerificationPort {

    private final AwsClients clients;

    public EcrArtifactAdapter(AwsClients clients) {
        this.clients = clients;
    }

    @Override
    public ConnectorType type() {
        return ConnectorType.AWS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.ARTIFACT_DISCOVERY, ConnectorCapability.ARTIFACT_VERIFICATION);
    }

    @Override
    public List<DiscoveredResource> discover(ConnectorContext context) {
        EcrClient ecr = clients.ecr(context);
        List<DiscoveredResource> found = new ArrayList<>();
        for (Repository repository : ecr.describeRepositoriesPaginator().repositories()) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("repositoryArn", repository.repositoryArn());
            metadata.put("repositoryUri", repository.repositoryUri());
            metadata.put("imageTagMutability", repository.imageTagMutabilityAsString());
            metadata.put("scanOnPush", String.valueOf(repository.imageScanningConfiguration() != null
                && Boolean.TRUE.equals(repository.imageScanningConfiguration().scanOnPush())));
            found.add(DiscoveredResource.of(context.integration().id(), ConnectorType.AWS,
                DiscoveredResourceType.ARTIFACT_REPOSITORY, repository.repositoryName(),
                repository.repositoryName(), clients.region(context).id(), metadata));
        }
        return found;
    }

    @Override
    public Optional<ArtifactDescriptor> findArtifactByDigest(ConnectorContext context,
                                                             String repositoryExternalId,
                                                             String digest) {
        if (digest == null || digest.isBlank()) {
            return Optional.empty();
        }
        EcrClient ecr = clients.ecr(context);
        var response = ecr.describeImages(DescribeImagesRequest.builder()
            .repositoryName(repositoryExternalId)
            .imageIds(ImageIdentifier.builder().imageDigest(digest).build())
            .build());
        return response.imageDetails().stream().findFirst().map(EcrArtifactAdapter::toDescriptor);
    }

    private static ArtifactDescriptor toDescriptor(ImageDetail detail) {
        return new ArtifactDescriptor(
            detail.repositoryName(),
            detail.imageDigest(),
            detail.imageTags(),
            detail.imagePushedAt(),
            detail.imageSizeInBytes() == null ? 0 : detail.imageSizeInBytes());
    }
}
