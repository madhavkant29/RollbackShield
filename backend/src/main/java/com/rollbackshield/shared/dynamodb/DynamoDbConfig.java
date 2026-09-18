package com.rollbackshield.shared.dynamodb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB client beans, active only when rollbackshield.persistence=dynamodb.
 * Credentials come from the default AWS credential provider chain -- an ECS
 * task role in deployment, never static keys in configuration (§46).
 */
@Configuration
@ConditionalOnProperty(name = "rollbackshield.persistence", havingValue = "dynamodb")
public class DynamoDbConfig {

    @Bean
    public DynamoDbClient dynamoDbClient(@Value("${rollbackshield.aws.region}") String region) {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder()
            .dynamoDbClient(client)
            .build();
    }
}
