package com.sitionix.forgeagent.infrastructure.codex;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodexAppServerProperties.class)
class CodexInfrastructureConfiguration {
}
