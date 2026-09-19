package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.ConnectorType;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationCredentialReference;
import com.rollbackshield.integrations.domain.connector.ArtifactDescriptor;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.shared.domain.OrganizationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ecr.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesResponse;
import software.amazon.awssdk.services.ecr.model.ImageDetail;
import software.amazon.awssdk.services.ecr.model.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins artifact existence semantics: a digest either exists or it does not,
 * and a blank digest must not produce an AWS call at all. ECR is not
 * emulated by LocalStack community; live verification is Phase 9.
 */
@ExtendWith(MockitoExtension.class)
class EcrArtifactAdapterTest {

    private static final String DIGEST = "sha256:abc123";

    @Mock
    private AwsClients clients;
    @Mock
    private EcrClient ecr;

    private EcrArtifactAdapter adapter;
    private ConnectorContext context;

    @BeforeEach
    void setUp() {
        adapter = new EcrArtifactAdapter(clients);
        Integration integration = Integration.pending(OrganizationId.newId(), "hackathon",
            ConnectorType.AWS, "us-east-1",
            IntegrationCredentialReference.awsControlPlaneRole(), Map.of());
        context = new ConnectorContext(integration, new CredentialMaterial.ControlPlaneRole());
    }

    @Test
    void findsArtifactByImmutableDigest() {
        when(clients.ecr(any())).thenReturn(ecr);
        when(ecr.describeImages(any(DescribeImagesRequest.class))).thenReturn(DescribeImagesResponse.builder()
            .imageDetails(ImageDetail.builder().repositoryName("payments").imageDigest(DIGEST)
                .imageTags("v41", "v42").imagePushedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .imageSizeInBytes(120L).build())
            .build());

        Optional<ArtifactDescriptor> artifact = adapter.findArtifactByDigest(context, "payments", DIGEST);

        assertTrue(artifact.isPresent());
        assertEquals(DIGEST, artifact.get().digest());
        assertEquals(List.of("v41", "v42"), artifact.get().tags());
        assertEquals(120L, artifact.get().sizeBytes());
    }

    @Test
    void missingDigestIsReportedAsAbsentNotAsAnError() {
        when(clients.ecr(any())).thenReturn(ecr);
        when(ecr.describeImages(any(DescribeImagesRequest.class)))
            .thenReturn(DescribeImagesResponse.builder().build());

        assertTrue(adapter.findArtifactByDigest(context, "payments", "sha256:missing").isEmpty());
    }

    @Test
    void blankDigestDoesNotCallAwsAtAll() {
        assertTrue(adapter.findArtifactByDigest(context, "payments", "  ").isEmpty());
        verify(ecr, never()).describeImages(any(DescribeImagesRequest.class));
    }

    @Test
    void discoversRepositories() {
        when(clients.ecr(any())).thenReturn(ecr);
        when(clients.region(any())).thenReturn(Region.US_EAST_1);
        software.amazon.awssdk.services.ecr.paginators.DescribeRepositoriesIterable iterable =
            org.mockito.Mockito.mock(software.amazon.awssdk.services.ecr.paginators.DescribeRepositoriesIterable.class);
        Repository repository = Repository.builder()
            .repositoryName("payments").repositoryArn("arn:aws:ecr:us-east-1:111122223333:repository/payments")
            .repositoryUri("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments")
            .imageTagMutability("MUTABLE").build();
        when(iterable.repositories()).thenReturn(() -> List.of(repository).iterator());
        when(ecr.describeRepositoriesPaginator()).thenReturn(iterable);

        List<DiscoveredResource> resources = adapter.discover(context);

        assertEquals(1, resources.size());
        assertEquals("payments", resources.get(0).externalId());
        assertEquals("111122223333.dkr.ecr.us-east-1.amazonaws.com/payments",
            resources.get(0).metadata().get("repositoryUri"));
    }
}
