package com.rollbackshield.integrations.domain.connector;

import com.rollbackshield.integrations.domain.DiscoveredResource;

import java.util.List;

/** A connector that can enumerate databases and inspect their schema objects. */
public interface DatabaseDiscoveryPort {

    List<DiscoveredResource> discoverDatabases(ConnectorContext context);

    List<DatabaseObject> discoverSchemaObjects(ConnectorContext context, String databaseExternalId);
}
