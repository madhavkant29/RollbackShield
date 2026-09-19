package com.rollbackshield.integrations.domain;

/**
 * Connection states shown in the integrations UI. CONNECTED is only ever
 * set after a real connector connection test succeeds against the provider
 * (or, for webhook-only systems, after a verified callback). There is no
 * code path that sets CONNECTED from configuration alone.
 */
public enum ConnectionState {
    CONNECTING,
    CONNECTED,
    ERROR,
    DISCONNECTED
}
