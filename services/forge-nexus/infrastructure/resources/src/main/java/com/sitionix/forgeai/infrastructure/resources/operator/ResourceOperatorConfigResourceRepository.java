package com.sitionix.forgeai.infrastructure.resources.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.operator.OperatorConfigResource;
import com.sitionix.forgeai.domain.repository.OperatorConfigResourceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResourceOperatorConfigResourceRepository implements OperatorConfigResourceRepository {

    private static final String AGENT_YML_KEY = "agent-yml";
    private static final String LANE_STRATEGIES_YML_KEY = "lane-strategies-yml";
    private static final String INSTRUCTION_KEY_PREFIX = "instruction:";
    private static final String CONTRACT_KEY_PREFIX = "contract:";
    private static final Pattern CONTRACT_TYPE_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    private final ObjectMapper objectMapper;

    @Override
    public OperatorConfigResource agentYaml() {
        return this.resource(AGENT_YML_KEY, "agent.yml", "yaml", this.agentYamlPath(this.repoRoot()));
    }

    @Override
    public OperatorConfigResource laneStrategiesYaml() {
        return this.resource(LANE_STRATEGIES_YML_KEY, "lane-strategies.yml", "yaml", this.laneStrategiesYamlPath(this.repoRoot()));
    }

    @Override
    public OperatorConfigResource instruction(final String instructionRef) {
        return this.resource(INSTRUCTION_KEY_PREFIX + instructionRef, instructionRef, "markdown", this.instructionPath(this.repoRoot(), instructionRef));
    }

    @Override
    public OperatorConfigResource contract(final String payloadType) {
        return this.resource(CONTRACT_KEY_PREFIX + payloadType, payloadType, "json", this.contractPath(this.repoRoot(), payloadType));
    }

    @Override
    public List<OperatorConfigResource> contracts() {
        final Path contractsPath = this.contractsPath(this.repoRoot());
        if (!Files.isDirectory(contractsPath)) {
            return List.of();
        }
        try (var paths = Files.list(contractsPath)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .map(this::contract)
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list payload contracts", e);
        }
    }

    @Override
    public OperatorConfigResource save(final String resourceKey, final String content) {
        if (resourceKey == null || resourceKey.isBlank()) {
            throw new IllegalArgumentException("resourceKey is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        final ResourceTarget target = this.target(this.repoRoot(), resourceKey);
        this.validateContent(target, content);
        this.write(target.path(), content);
        return this.resource(target.resourceKey(), target.label(), target.resourceType(), target.path());
    }

    private ResourceTarget target(final Path root, final String resourceKey) {
        if (AGENT_YML_KEY.equals(resourceKey)) {
            return new ResourceTarget(resourceKey, "agent.yml", "yaml", this.agentYamlPath(root));
        }
        if (LANE_STRATEGIES_YML_KEY.equals(resourceKey)) {
            return new ResourceTarget(resourceKey, "lane-strategies.yml", "yaml", this.laneStrategiesYamlPath(root));
        }
        if (resourceKey.startsWith(INSTRUCTION_KEY_PREFIX)) {
            final String ref = resourceKey.substring(INSTRUCTION_KEY_PREFIX.length());
            return new ResourceTarget(resourceKey, ref, "markdown", this.instructionPath(root, ref));
        }
        if (resourceKey.startsWith(CONTRACT_KEY_PREFIX)) {
            final String payloadType = resourceKey.substring(CONTRACT_KEY_PREFIX.length());
            return new ResourceTarget(resourceKey, payloadType, "json", this.contractPath(root, payloadType));
        }
        throw new IllegalArgumentException("Unsupported config resourceKey: " + resourceKey);
    }

    private OperatorConfigResource resource(final String resourceKey, final String label, final String resourceType, final Path path) {
        return OperatorConfigResource.builder()
                .resourceKey(resourceKey)
                .label(label)
                .resourceType(resourceType)
                .path(path.toString())
                .writable(Files.isRegularFile(path) && Files.isWritable(path))
                .content(this.read(path))
                .build();
    }

    private void validateContent(final ResourceTarget target, final String content) {
        if (content.isBlank()) {
            throw new IllegalArgumentException("Resource content must not be blank: " + target.resourceKey());
        }
        if ("yaml".equals(target.resourceType())) {
            final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
            if (yaml.getObject() == null) {
                throw new IllegalArgumentException("Invalid YAML content: " + target.resourceKey());
            }
        }
        if ("json".equals(target.resourceType())) {
            try {
                this.objectMapper.readTree(content);
            } catch (final IOException e) {
                throw new IllegalArgumentException("Invalid JSON content: " + target.resourceKey(), e);
            }
        }
    }

    private String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read resource: " + path, e);
        }
    }

    private void write(final Path path, final String content) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Resource file does not exist: " + path);
        }
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write resource: " + path, e);
        }
    }

    private Path repoRoot() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (path != null) {
            if (Files.isRegularFile(path.resolve("pom.xml")) && this.hasForgeAiResources(path)) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Unable to locate forge-ai repository root from user.dir=" + System.getProperty("user.dir"));
    }

    private Path agentYamlPath(final Path root) {
        return this.rootConfigPath(root, "agent.yml");
    }

    private Path laneStrategiesYamlPath(final Path root) {
        return this.rootConfigPath(root, "lane-strategies.yml");
    }

    private Path rootConfigPath(final Path root, final String fileName) {
        return this.configDir(root).resolve(fileName).normalize();
    }

    private Path configDir(final Path root) {
        final String configured = System.getenv("FORGE_CONFIG_DIR");
        if (configured != null && !configured.isBlank()) {
            final Path path = Path.of(configured);
            return (path.isAbsolute() ? path : root.resolve(path)).normalize();
        }
        final Path rootConfig = root.resolve("config").normalize();
        return rootConfig;
    }

    private Path instructionPath(final Path root, final String ref) {
        final Path base = root.resolve("services/forge-nexus/infrastructure/resources/src/main/resources/instructions").normalize();
        final String normalizedRef = ref.startsWith("instructions/")
                ? ref.substring("instructions/".length())
                : ref;
        final Path path = base.resolve(normalizedRef).normalize();
        if (!path.startsWith(base) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unsupported instruction ref: " + ref);
        }
        return path;
    }

    private Path contractsPath(final Path root) {
        return root.resolve("services/forge-nexus/infrastructure/resources/src/main/resources/completion-payload-contracts").normalize();
    }

    private boolean hasForgeAiResources(final Path root) {
        return Files.isDirectory(root.resolve("config"))
                || Files.isDirectory(root.resolve("services/forge-nexus/infrastructure/resources/src/main/resources"));
    }

    private Path contractPath(final Path root, final String payloadType) {
        if (!CONTRACT_TYPE_PATTERN.matcher(payloadType).matches()) {
            throw new IllegalArgumentException("Unsupported payload contract type: " + payloadType);
        }
        final Path path = this.contractsPath(root).resolve(payloadType + ".json").normalize();
        if (!path.startsWith(this.contractsPath(root)) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unsupported payload contract type: " + payloadType);
        }
        return path;
    }

    private record ResourceTarget(String resourceKey, String label, String resourceType, Path path) {
    }
}
