package com.sitionix.forgeagent.domain.model;

public record DockerLogConfiguration(String container, String composeService, String composeFile) implements LogProviderConfiguration {
}
