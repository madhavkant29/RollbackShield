package com.rollbackshield.reversibility.api;

import java.util.List;

public final class ReversibilityDtos {
    private ReversibilityDtos() {
    }

    public record CheckDto(String name, boolean passed, String blockerCode, String blockerDescription) {
    }

    public record ReversibilityReportResponse(String releaseId, String status, List<CheckDto> checks) {
    }
}
