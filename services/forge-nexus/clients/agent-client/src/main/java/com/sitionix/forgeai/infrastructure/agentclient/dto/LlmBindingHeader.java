package com.sitionix.forgeai.infrastructure.agentclient.dto;

/** Explicit sensitive header argument, handled without Spring's TRACE value logging. */
public record LlmBindingHeader(String value) {
    @Override public String toString(){return "LlmBindingHeader[redacted]";}
}
