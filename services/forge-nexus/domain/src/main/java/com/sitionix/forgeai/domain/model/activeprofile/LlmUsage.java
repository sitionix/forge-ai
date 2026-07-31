package com.sitionix.forgeai.domain.model.activeprofile;

import java.util.EnumSet;
import java.util.List;

public record LlmUsage(List<LlmUsageWindow> windows) {
    public LlmUsage {
        final List<LlmUsageWindow> requiredWindows = ActiveProfileInvariants.required(windows, "windows");
        if (requiredWindows.isEmpty() || requiredWindows.size() > 2) {
            throw new IllegalArgumentException("windows must contain one or two entries");
        }
        final EnumSet<LlmUsageWindowKind> seenKinds = EnumSet.noneOf(LlmUsageWindowKind.class);
        for (final LlmUsageWindow window : requiredWindows) {
            final LlmUsageWindow requiredWindow = ActiveProfileInvariants.required(window, "window");
            if (!seenKinds.add(requiredWindow.kind())) {
                throw new IllegalArgumentException("windows must not contain duplicate kinds");
            }
        }
        windows = List.copyOf(requiredWindows);
    }
}
