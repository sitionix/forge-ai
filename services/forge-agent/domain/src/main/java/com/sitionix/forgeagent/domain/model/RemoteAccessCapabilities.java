package com.sitionix.forgeagent.domain.model;
import java.util.List;
public record RemoteAccessCapabilities(boolean ready, List<String> supportedOperations, List<String> diagnostics) {}
