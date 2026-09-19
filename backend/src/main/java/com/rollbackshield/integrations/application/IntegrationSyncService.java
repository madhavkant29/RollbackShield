package com.rollbackshield.integrations.application;

import com.rollbackshield.audit.AuditAction;
import com.rollbackshield.audit.AuditEvent;
import com.rollbackshield.audit.AuditTrail;
import com.rollbackshield.integrations.domain.ConnectorHealth;
import com.rollbackshield.integrations.domain.CredentialMaterial;
import com.rollbackshield.integrations.domain.CredentialResolver;
import com.rollbackshield.integrations.domain.DiscoveredResource;
import com.rollbackshield.integrations.domain.DiscoveredResourceRepository;
import com.rollbackshield.integrations.domain.Integration;
import com.rollbackshield.integrations.domain.IntegrationRepository;
import com.rollbackshield.integrations.domain.SyncResult;
import com.rollbackshield.integrations.domain.connector.CapabilityProvider;
import com.rollbackshield.integrations.domain.connector.ConnectorContext;
import com.rollbackshield.integrations.domain.connector.DiscoveryContributionPort;
import com.rollbackshield.shared.events.domain.DomainEvent;
import com.rollbackshield.shared.events.domain.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a full discovery pass over an integration. Sync is asynchronous by
 * contract (callers never block application hot paths on it) and honest by
 * behavior: if any provider fails, the previous resource set is kept and the
 * failure is reported, rather than replacing real state with a partial one.
 */
@Service
public class IntegrationSyncService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationSyncService.class);

    private final IntegrationRepository integrations;
    private final DiscoveredResourceRepository resources;
    private final ConnectorRegistry connectors;
    private final CredentialResolver credentials;
    private final AuditTrail auditTrail;
    private final EventPublisher events;

    public IntegrationSyncService(IntegrationRepository integrations, DiscoveredResourceRepository resources,
                                  ConnectorRegistry connectors, CredentialResolver credentials,
                                  AuditTrail auditTrail, EventPublisher events) {
        this.integrations = integrations;
        this.resources = resources;
        this.connectors = connectors;
        this.credentials = credentials;
        this.auditTrail = auditTrail;
        this.events = events;
    }

    public SyncResult sync(Integration integration) {
        Integration attempted = integration.recordSyncAttempt();
        integrations.save(attempted);

        ConnectorContext context;
        try {
            CredentialMaterial material = credentials.resolve(attempted.credential());
            context = new ConnectorContext(attempted, material);
        } catch (RuntimeException e) {
            return fail(attempted, List.of("credentials: " + safeMessage(e)));
        }

        List<DiscoveredResource> discovered = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (CapabilityProvider provider : connectors.providersFor(attempted.type())) {
            if (provider instanceof DiscoveryContributionPort contribution) {
                try {
                    discovered.addAll(contribution.discover(context));
                } catch (RuntimeException e) {
                    errors.add(provider.getClass().getSimpleName() + ": " + safeMessage(e));
                }
            }
        }

        if (!errors.isEmpty()) {
            return fail(attempted, errors);
        }

        resources.replaceForIntegration(attempted.id(), discovered);
        Integration updated = attempted.recordSyncSuccess(new ConnectorHealth(
            ConnectorHealth.HealthState.HEALTHY, "discovered " + discovered.size() + " resources",
            Instant.now()), discovered.size());
        integrations.save(updated);
        SyncResult result = SyncResult.success(updated.id(), discovered.size(), 0);

        auditTrail.append(AuditEvent.of(updated.organizationId().toString(), updated.id().toString(), "system",
            AuditAction.INTEGRATION_SYNC_COMPLETED, updated.id().toString(),
            "discovered " + discovered.size() + " resources", null, null, result.toEventPayload()));
        events.publish(DomainEvent.of("IntegrationSyncCompleted", updated.organizationId().toString(),
            updated.id().toString(), result.toEventPayload()));
        return result;
    }

    private SyncResult fail(Integration integration, List<String> errors) {
        String summary = String.join("; ", errors);
        Integration failed = integration.recordSyncFailure(summary);
        integrations.save(failed);
        SyncResult result = new SyncResult(integration.id(), 0, 0, errors.size(), errors, Instant.now());

        auditTrail.append(AuditEvent.of(failed.organizationId().toString(), failed.id().toString(), "system",
            AuditAction.INTEGRATION_SYNC_FAILED, failed.id().toString(), summary, null, null, Map.of()));
        events.publish(DomainEvent.of("IntegrationSyncFailed", failed.organizationId().toString(),
            failed.id().toString(), Map.of("errorCount", String.valueOf(errors.size()))));
        log.warn("integration sync failed integration={} errors={}", integration.id(), summary);
        return result;
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
