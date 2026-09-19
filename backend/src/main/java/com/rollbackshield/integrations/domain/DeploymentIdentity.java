package com.rollbackshield.integrations.domain;

import java.util.Objects;

/**
 * Everything needed to identify the candidate and previous deployment of a
 * service in a runtime. Produced by a connector observing the real runtime;
 * a revision or digest is never guessed from a label.
 */
public record DeploymentIdentity(
    String runtimeName,
    String runtimeExternalId,
    String candidateRevision,
    String previousRevision,
    String candidateArtifactDigest,
    String previousArtifactDigest,
    String commitSha,
    String branch,
    String artifactRepository,
    String deploymentStatus
) {

    public DeploymentIdentity {
        Objects.requireNonNull(runtimeName, "runtimeName");
        Objects.requireNonNull(runtimeExternalId, "runtimeExternalId");
        Objects.requireNonNull(candidateRevision, "candidateRevision");
    }

    public boolean hasPreviousRevision() {
        return previousRevision != null && !previousRevision.isBlank();
    }

    public String candidateVersionLabel() {
        return runtimeName + "@" + shortRevision(candidateRevision);
    }

    public String previousVersionLabel() {
        return hasPreviousRevision()
            ? runtimeName + "@" + shortRevision(previousRevision)
            : runtimeName + "@none";
    }

    /**
     * Trims a provider revision identifier to its human-meaningful tail
     * (task-definition:42, a Kubernetes ReplicaSet hash, a deployment id).
     */
    public static String shortRevision(String revision) {
        if (revision == null || revision.isBlank()) {
            return "unknown";
        }
        String value = revision;
        int taskDefinition = value.indexOf("task-definition/");
        if (taskDefinition >= 0) {
            String tail = value.substring(taskDefinition + "task-definition/".length());
            int colon = tail.lastIndexOf(':');
            return "task-definition:" + (colon >= 0 ? tail.substring(colon + 1) : tail);
        }
        int lastSlash = value.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < value.length() - 1) {
            return value.substring(lastSlash + 1);
        }
        return value;
    }

    /** Immutable digest from an image reference, or null when only a mutable tag is used. */
    public static String artifactDigest(String image) {
        if (image == null) {
            return null;
        }
        int at = image.indexOf('@');
        return at >= 0 && at < image.length() - 1 ? image.substring(at + 1) : null;
    }

    /** Repository part of an image reference (registry/repository, without tag or digest). */
    public static String artifactRepository(String image) {
        if (image == null) {
            return null;
        }
        int at = image.indexOf('@');
        String withoutDigest = at >= 0 ? image.substring(0, at) : image;
        int lastSlash = withoutDigest.lastIndexOf('/');
        int lastColon = withoutDigest.lastIndexOf(':');
        return lastColon > lastSlash ? withoutDigest.substring(0, lastColon) : withoutDigest;
    }
}
