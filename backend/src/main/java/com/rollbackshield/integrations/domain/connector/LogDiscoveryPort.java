package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.DiscoveredResource;

import java.time.Instant;
import java.util.List;

/** A connector that can enumerate log groups and surface bounded evidence from them. */
public interface LogDiscoveryPort {

    List<DiscoveredResource> discoverLogGroups(ConnectorContext context);

    /**
     * Recent error evidence for a log group since the given instant. Must be
     * bounded (implementations cap the number of events returned); this is
     * evidence, not log ingestion.
     */
    List<LogEvidence> recentErrors(ConnectorContext context, String logGroupName, Instant since, int limit);
}
