package com.sitionix.forgeai.api.activeprofile;

import java.util.List;

public record LlmUsageResponse(List<LlmUsageWindowResponse> windows) {
}
