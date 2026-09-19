package com.rollbackshield.integration;

import com.rollbackshield.integrations.adapter.DynamoDbDeploymentObservationRepository;
import com.rollbackshield.integrations.adapter.DynamoDbDiscoveredResourceRepository;
import com.rollbackshield.integrations.adapter.DynamoDbIntegrationRepository;
import com.rollbackshield.integrations.adapter.DynamoDbServiceMappingRepository;
import com.rollbackshield.integrations.domain.DeploymentObservationRepository;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.integrations.domain.ServiceMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 'aws' profile must start with DynamoDB-backed connectivity
 * persistence. Before this suite existed, the profile failed with "no bean
 * of type ServiceMappingRepository"; this test pins that every connectivity
 * repository resolves to its DynamoDB adapter under the deployed profile.
 *
 * The JWT decoder is configured with a JWK set URI (no startup fetch) so
 * the context can be built without a real Cognito user pool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("aws")
@TestPropertySource(properties = {
    "COGNITO_ISSUER_URI=https://cognito-idp.ap-south-1.amazonaws.com/example",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/jwks",
    "rollbackshield.sqs.queue-url=https://sqs.us-east-1.amazonaws.com/000000000000/rollbackshield-work",
    "rollbackshield.aws.region=us-east-1"
})
class AwsProfileContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void awsProfileResolvesTheCognitoIssuerFromTheEnvironment() {
        // Regression: the issuer used to be declared under top-level
        // 'security:' instead of 'spring.security:', so the deployed task
        // started with no JwtDecoder bean. The property must resolve from
        // COGNITO_ISSUER_URI exactly as ECS provides it.
        assertThat(context.getEnvironment().getProperty(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri"))
            .isEqualTo("https://cognito-idp.ap-south-1.amazonaws.com/example");
    }

    @Test
    void connectivityRepositoriesAreDynamoBackedInTheDeployedProfile() {
        assertThat(context.getBean(IntegrationRepository.class))
            .isInstanceOf(DynamoDbIntegrationRepository.class);
        assertThat(context.getBean(DiscoveredResourceRepository.class))
            .isInstanceOf(DynamoDbDiscoveredResourceRepository.class);
        assertThat(context.getBean(ServiceMappingRepository.class))
            .isInstanceOf(DynamoDbServiceMappingRepository.class);
        assertThat(context.getBean(DeploymentObservationRepository.class))
            .isInstanceOf(DynamoDbDeploymentObservationRepository.class);
    }
}
