package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Write-only from the server's perspective: the outbound request must serialize this value. */
public record LlmBindingOutbound(@JsonProperty String browserBinding) {
    @Override public String toString(){return "LlmBindingOutbound[redacted]";}
}
