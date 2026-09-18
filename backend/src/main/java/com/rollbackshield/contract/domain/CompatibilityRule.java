package com.rollbackshield.contract.domain;

import java.util.List;
import java.util.Set;

/**
 * A single deterministic compatibility rule within a rollback contract.
 *
 * This is intentionally a small, closed set (§8 of the product spec: "do not
 * build a programming language"). Every rule answers one yes/no question
 * about whether a previous release version can still make sense of a value
 * the candidate release wants to write.
 */
public sealed interface CompatibilityRule
    permits CompatibilityRule.EnumAllowedValues,
            CompatibilityRule.Nullability,
            CompatibilityRule.NumericRange,
            CompatibilityRule.RequiredField,
            CompatibilityRule.ForbiddenValue {

    String entity();

    String field();

    /**
     * The previous release can only read these enum values. Anything else
     * (e.g. a new value like PARTIALLY_REFUNDED introduced by the candidate)
     * crosses the rollback horizon.
     */
    record EnumAllowedValues(String entity, String field, Set<String> previousVersionSupports)
        implements CompatibilityRule {

        public EnumAllowedValues {
            if (previousVersionSupports == null || previousVersionSupports.isEmpty()) {
                throw new IllegalArgumentException("previousVersionSupports must not be empty");
            }
            previousVersionSupports = Set.copyOf(previousVersionSupports);
        }
    }

    /**
     * Whether the previous release's reader can tolerate a null value for
     * this field.
     */
    record Nullability(String entity, String field, boolean previousVersionAllowsNull)
        implements CompatibilityRule {
    }

    /**
     * The numeric range the previous release's reader can safely handle.
     */
    record NumericRange(String entity, String field, double min, double max)
        implements CompatibilityRule {

        public NumericRange {
            if (min > max) {
                throw new IllegalArgumentException("min must be <= max");
            }
        }
    }

    /**
     * The previous release requires this field to be present (non-missing,
     * independent of nullability).
     */
    record RequiredField(String entity, String field) implements CompatibilityRule {
    }

    /**
     * Values the previous release's reader is known to choke on even if they
     * are otherwise structurally valid (e.g. a sentinel the old code
     * special-cases incorrectly).
     */
    record ForbiddenValue(String entity, String field, Set<String> forbiddenValues)
        implements CompatibilityRule {

        public ForbiddenValue {
            if (forbiddenValues == null || forbiddenValues.isEmpty()) {
                throw new IllegalArgumentException("forbiddenValues must not be empty");
            }
            forbiddenValues = Set.copyOf(forbiddenValues);
        }
    }

    /** Convenience for building an immutable rule list. */
    static List<CompatibilityRule> listOf(CompatibilityRule... rules) {
        return List.of(rules);
    }
}
