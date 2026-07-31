package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record KnowledgeLlmUsage(List<KnowledgeLlmUsageWindow> windows) {
}
