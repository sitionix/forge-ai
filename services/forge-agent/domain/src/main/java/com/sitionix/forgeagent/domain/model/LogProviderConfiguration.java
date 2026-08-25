package com.sitionix.forgeagent.domain.model;

public sealed interface LogProviderConfiguration permits DockerLogConfiguration, SystemdLogConfiguration, FileLogConfiguration {
}
