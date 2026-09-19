package com.rollbackshield.reversibility.api;

import java.util.List;

public final class ReversibilityDtos {
    private ReversibilityDtos() {
    }

    public record CheckDto(String name, boolean passed, String blockerCode, String blockerDescription,
                           String blockerSeverity) {
    }

    public record EvidenceDto(String subject, String relation, String object, String source, String detail) {
    }

    public record BlockerPathDto(String code, String severity, String description, List<EvidenceDto> path) {
    }

    public record ReversibilityReportResponse(String releaseId, String status, String verdict,
                                               List<CheckDto> checks, List<EvidenceDto> evidence,
                                               List<BlockerPathDto> blockerPaths,
                                               String evaluatedAt) {
    }
}
