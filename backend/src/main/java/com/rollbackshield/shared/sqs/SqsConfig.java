package com.rollbackshield.shared.sqs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@ConditionalOnProperty(name = "rollbackshield.workqueue", havingValue = "sqs")
public class SqsConfig {

    @Bean
    public SqsClient sqsClient(@Value("${rollbackshield.aws.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).build();
    }
}
