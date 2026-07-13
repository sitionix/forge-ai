package com.sitionix.forgeai.api.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class ForgeAiFlowExplanationConfigurationContractTest {

    @Test
    void rootForgeAiYamlBindsFlowExplanationTimeout() {
        final ForgeAiFlowExplanationProperties properties = bindRootFlowExplanationProperties(Map.of());
        final InfrastructureProxyProperties infrastructureProperties = bindRootInfrastructureProperties(Map.of());

        assertThat(properties.getRequestTimeoutSeconds()).isEqualTo(180);
        assertThat(infrastructureProperties.getProxy().knowledgeExplanationReadTimeout(properties.requestTimeout()))
                .isEqualTo(Duration.ofSeconds(185));
        assertThat(infrastructureProperties.getProxy().jarvisQueryReadTimeout(properties.requestTimeout()))
                .isEqualTo(Duration.ofSeconds(190));
    }

    @Test
    void sharedEnvironmentOverrideBindsFlowExplanationTimeoutAndNexusTransportGrace() {
        final ForgeAiFlowExplanationProperties flowExplanationProperties = bindRootFlowExplanationProperties(Map.of(
                "FORGE_FLOW_EXPLANATION_REQUEST_TIMEOUT_SECONDS",
                "73"
        ));
        final InfrastructureProxyProperties infrastructureProperties = bindRootInfrastructureProperties(Map.of(
                "FORGE_FLOW_EXPLANATION_REQUEST_TIMEOUT_SECONDS",
                "73"
        ));

        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                infrastructureProperties,
                flowExplanationProperties
        );

        assertThat(flowExplanationProperties.getRequestTimeoutSeconds()).isEqualTo(73);
        assertThat(registry.require("knowledge.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(78));
        assertThat(registry.require("knowledge.query.tool-context").readTimeout())
                .isEqualTo(Duration.ofSeconds(78));
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofSeconds(83));
    }

    @Test
    void durationDeadlineCanBindForDeterministicTimeoutHierarchyTests() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", Map.of(
                "forge.ai.query.flow-explanation.request-timeout",
                "100ms"
        )));
        final ForgeAiFlowExplanationProperties flowExplanationProperties = Binder.get(environment)
                .bind("forge.ai.query.flow-explanation", ForgeAiFlowExplanationProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind test flow explanation settings"));
        final InfrastructureProxyProperties infrastructureProperties = new InfrastructureProxyProperties();
        infrastructureProperties.getProxy().setKnowledgeExplanationTransportGrace(Duration.ofMillis(50));
        infrastructureProperties.getProxy().setJarvisQueryTransportGrace(Duration.ofMillis(50));

        final InfrastructureProxyRouteRegistry registry = new InfrastructureProxyRouteRegistry(
                infrastructureProperties,
                flowExplanationProperties
        );

        assertThat(registry.require("knowledge.query").readTimeout())
                .isEqualTo(Duration.ofMillis(150));
        assertThat(registry.require("jarvis.query").readTimeout())
                .isEqualTo(Duration.ofMillis(200));
    }

    private static ForgeAiFlowExplanationProperties bindRootFlowExplanationProperties(
            final Map<String, Object> overrides
    ) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        for (final PropertySource<?> propertySource : rootForgeAiYaml()) {
            environment.getPropertySources().addLast(propertySource);
        }
        return Binder.get(environment)
                .bind("forge.ai.query.flow-explanation", ForgeAiFlowExplanationProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind root flow explanation settings"));
    }

    private static InfrastructureProxyProperties bindRootInfrastructureProperties(
            final Map<String, Object> overrides
    ) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        for (final PropertySource<?> propertySource : rootForgeAiYaml()) {
            environment.getPropertySources().addLast(propertySource);
        }
        return Binder.get(environment)
                .bind("forge.ai.infrastructure", InfrastructureProxyProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind root infrastructure settings"));
    }

    private static List<PropertySource<?>> rootForgeAiYaml() {
        try {
            final Path path = rootConfigPath();
            return new YamlPropertySourceLoader().load("forge-ai", new FileSystemResource(path));
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to load root forge-ai.yaml", ex);
        }
    }

    private static Path rootConfigPath() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            final Path candidate = cursor.resolve("config").resolve("forge-ai.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Could not locate config/forge-ai.yaml");
    }
}
