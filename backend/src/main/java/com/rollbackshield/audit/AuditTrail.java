package com.rollbackshield.audit;

import java.util.List;

/**
 * Append-only audit log. No update/delete methods exist on this interface by
 * design (§31: "do not expose editing/deleting in normal UI") — the only
 * operations are append and list.
 */
public interface AuditTrail {

    void append(AuditEvent event);

    List<AuditEvent> listForRelease(String releaseId);
}
