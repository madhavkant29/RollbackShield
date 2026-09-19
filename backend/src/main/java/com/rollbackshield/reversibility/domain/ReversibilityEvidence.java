package com.rollbackshield.reversibility.domain;

import java.util.Objects;

/**
 * A single "why" edge in the reversibility graph, phrased as
 * subject -> relation -> object with the source that produced it. Evidence
 * is what the UI renders instead of a decorative graph: every claim points
 * at a real observation.
 */
public record ReversibilityEvidence(
    String subject,
    String relation,
    String object,
    String source,
    String detail
) {

    public ReversibilityEvidence {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(source, "source");
        detail = detail == null ? "" : detail;
    }
}
