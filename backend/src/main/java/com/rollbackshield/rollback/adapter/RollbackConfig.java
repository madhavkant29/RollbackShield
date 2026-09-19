package com.rollbackshield.rollback.adapter;

import com.rollbackshield.rollback.application.RollbackMonitorPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RollbackConfig {

    @Bean
    public RollbackMonitorPolicy rollbackMonitorPolicy(
        @Value("${rollbackshield.rollback.monitor-timeout-seconds:600}") long timeoutSeconds,
        @Value("${rollbackshield.rollback.monitor-interval-seconds:5}") long intervalSeconds) {
        return new RollbackMonitorPolicy(Duration.ofSeconds(timeoutSeconds),
            Duration.ofSeconds(intervalSeconds));
    }
}
