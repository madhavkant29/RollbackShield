package com.rollbackshield.connectors.aws.adapter;

import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches AWS service clients per (region, endpoint, credentials)
 * so connector calls reuse HTTP connection pools instead of creating a new
 * client -- and a new TLS handshake -- on every call. Credentials come from
 * either the control plane's own role (hackathon mode) or STS AssumeRole
 * (production customer path); static customer keys are never accepted.
 */
@Component
public class AwsClients {

    private static final String CONFIG_ENDPOINT_OVERRIDE = "endpointOverride";
    private static final String ASSUME_ROLE_SESSION_NAME = "rollbackshield-observation";

    private final Map<String, Object> cache = new ConcurrentHashMap<>();
    private final Map<String, AwsCredentialsProvider> assumeRoleProviders = new ConcurrentHashMap<>();

    public StsClient sts(ConnectorContext context) {
        return cached(context, StsClient.class,
            () -> builder(StsClient.builder(), context).build());
    }

    public EcsClient ecs(ConnectorContext context) {
        return cached(context, EcsClient.class,
            () -> builder(EcsClient.builder(), context).build());
    }

    public EcrClient ecr(ConnectorContext context) {
        return cached(context, EcrClient.class,
            () -> builder(EcrClient.builder(), context).build());
    }

    public SqsClient sqs(ConnectorContext context) {
        return cached(context, SqsClient.class,
            () -> builder(SqsClient.builder(), context).build());
    }

    public CloudWatchLogsClient logs(ConnectorContext context) {
        return cached(context, CloudWatchLogsClient.class,
            () -> builder(CloudWatchLogsClient.builder(), context).build());
    }

    public EventBridgeClient events(ConnectorContext context) {
        return cached(context, EventBridgeClient.class,
            () -> builder(EventBridgeClient.builder(), context).build());
    }

    public Region region(ConnectorContext context) {
        String configured = context.config("region", context.endpoint());
        return Region.of(configured == null || configured.isBlank() ? "us-east-1" : configured);
    }

    private <T, B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, T>> B builder(
        B builder, ConnectorContext context) {
        builder.region(region(context)).credentialsProvider(credentials(context));
        URI endpoint = endpointOverride(context);
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder;
    }

    private <T> T cached(ConnectorContext context, Class<T> type, java.util.function.Supplier<T> factory) {
        String key = type.getSimpleName() + "|" + context.integration().id() + "|" + region(context)
            + "|" + credentialFingerprint(context) + "|" + String.valueOf(endpointOverride(context));
        return type.cast(cache.computeIfAbsent(key, ignored -> factory.get()));
    }

    private AwsCredentialsProvider credentials(ConnectorContext context) {
        CredentialMaterial material = context.credentials();
        if (material instanceof CredentialMaterial.ControlPlaneRole) {
            return DefaultCredentialsProvider.create();
        }
        if (material instanceof CredentialMaterial.AssumedRole assumedRole) {
            String key = assumedRole.roleArn() + "|" + assumedRole.externalId();
            return assumeRoleProviders.computeIfAbsent(key, ignored -> {
                StsClient sts = stsWithDefaultCredentials(context);
                return StsAssumeRoleCredentialsProvider.builder()
                    .stsClient(sts)
                    .refreshRequest(request -> {
                        request.roleArn(assumedRole.roleArn())
                            .roleSessionName(ASSUME_ROLE_SESSION_NAME);
                        if (assumedRole.externalId() != null && !assumedRole.externalId().isBlank()) {
                            request.externalId(assumedRole.externalId());
                        }
                    })
                    .build();
            });
        }
        throw new IllegalStateException("AWS connector received "
            + material.getClass().getSimpleName() + "; expected an AWS credential kind");
    }

    /**
     * The STS client used only to mint assumed-role sessions. Uses ambient
     * credentials (task role); a customer role can never be assumed with
     * customer-supplied static keys because none are accepted.
     */
    private StsClient stsWithDefaultCredentials(ConnectorContext context) {
        StsClientBuilder builder = StsClient.builder().region(region(context))
            .credentialsProvider(DefaultCredentialsProvider.create());
        URI endpoint = endpointOverride(context);
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    private static URI endpointOverride(ConnectorContext context) {
        String value = context.config(CONFIG_ENDPOINT_OVERRIDE, null);
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    private String credentialFingerprint(ConnectorContext context) {
        CredentialMaterial material = context.credentials();
        if (material instanceof CredentialMaterial.AssumedRole assumedRole) {
            return "assume:" + assumedRole.roleArn();
        }
        return "control-plane-role";
    }
}
