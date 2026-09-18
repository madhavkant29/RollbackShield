package com.rollbackshield.sdk;

import java.util.List;
import java.util.Set;

/**
 * The SDK's local copy of the compatibility rule vocabulary.
 *
 * Deliberately NOT shared as a Java dependency with the backend's
 * `contract.domain.CompatibilityRule` — the SDK is a separately-versioned
 * artifact that applications embed directly, so its only contract with the
 * control plane is the wire format (JSON) returned by the policy endpoint,
 * not a shared class file. See docs/adr/003-local-policy-evaluation.md.
 */
public sealed interface SdkCompatibilityRule
    permits SdkCompatibilityRule.EnumAllowedValues,
            SdkCompatibilityRule.Nullability,
            SdkCompatibilityRule.NumericRange,
            SdkCompatibilityRule.RequiredField,
            SdkCompatibilityRule.ForbiddenValue {

    String entity();

    String field();

    record EnumAllowedValues(String entity, String field, Set<String> previousVersionSupports)
        implements SdkCompatibilityRule {
        public EnumAllowedValues {
            previousVersionSupports = Set.copyOf(previousVersionSupports);
        }
    }

    record Nullability(String entity, String field, boolean previousVersionAllowsNull)
        implements SdkCompatibilityRule {
    }

    record NumericRange(String entity, String field, double min, double max)
        implements SdkCompatibilityRule {
    }

    record RequiredField(String entity, String field) implements SdkCompatibilityRule {
    }

    record ForbiddenValue(String entity, String field, Set<String> forbiddenValues)
        implements SdkCompatibilityRule {
        public ForbiddenValue {
            forbiddenValues = Set.copyOf(forbiddenValues);
        }
    }

    static List<SdkCompatibilityRule> listOf(SdkCompatibilityRule... rules) {
        return List.of(rules);
    }
}
