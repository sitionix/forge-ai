package com.sitionix.forgeai.api.activeprofile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ActiveLlmEffortRequest(String effortId) {
}
