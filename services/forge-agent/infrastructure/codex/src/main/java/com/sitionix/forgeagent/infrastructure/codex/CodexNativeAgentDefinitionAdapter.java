package com.sitionix.forgeagent.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.sitionix.forgeagent.application.definition.NativeAgentDefinitionPort;
import com.sitionix.forgeagent.application.runtime.ExecutionWorkspace;
import com.sitionix.forgeagent.domain.exception.ConflictException;
import com.sitionix.forgeagent.domain.model.AgentModelSelection;
import com.sitionix.forgeagent.domain.model.execution.EffectiveAgentDefinition;
import com.sitionix.forgeagent.domain.model.library.LibraryContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Maps the private helper protocol to the existing provider-neutral definition port. */
@Component
public final class CodexNativeAgentDefinitionAdapter implements NativeAgentDefinitionPort {
    private final CodexAgentDefinitionReader reader;

    @Autowired
    public CodexNativeAgentDefinitionAdapter(@Value("${forge.codex.library.global-home:}") String home) {
        this(new CodexAgentDefinitionReader(home.isBlank() ? null : Path.of(home)));
    }

    CodexNativeAgentDefinitionAdapter(CodexAgentDefinitionReader reader) { this.reader = reader; }

    @Override
    public EffectiveAgentDefinition resolve(UUID project, String reference, ExecutionWorkspace workspace, AgentModelSelection fallback) {
        if (reference == null || !reference.matches("[a-f0-9]{64}")) throw unavailable();
        var context = context(project, workspace);
        try {
            JsonNode response = reader.query(context, reference, false);
            JsonNode resolved = response.path("resolved");
            if (!response.path("diagnostics").isEmpty() || !resolved.isArray() || resolved.size() != 1) throw unavailable();
            JsonNode value = resolved.get(0);
            String identity = requiredText(value, "identity");
            if (!reader.ownsAgentPath(Path.of(identity), context) || !CodexAgentDefinitionReader.reference(identity).equals(reference)) throw unavailable();
            String fingerprint = requiredText(value, "fingerprint");
            if (!fingerprint.matches("[a-f0-9]{64}")) throw unavailable();
            String name = requiredText(value, "name");
            String instructions = requiredText(value, "instructions");
            if (name.length() > 512 || instructions.length() > 65536) throw unavailable();
            boolean codexFallback = fallback != null && "codex".equals(fallback.providerId());
            String model = optionalText(value, "model");
            if (model == null && codexFallback) model = fallback.modelId();
            if (model == null || model.isBlank()) throw new ConflictException("AGENT_MODEL_UNAVAILABLE", "Native agent has no effective model.");
            String effort = optionalText(value, "effort");
            if (effort == null && codexFallback) effort = fallback.effortId();
            return new EffectiveAgentDefinition("NATIVE_CODEX", reference, CodexLibraryMetadataMapper.safeText(name), instructions,
                    new AgentModelSelection("codex", model, effort), fingerprint, Map.of(identity, fingerprint), false);
        } catch (CodexAgentDefinitionReader.UnsupportedRuntimeException incompatible) {
            throw runtimeUnsupported();
        } catch (ConflictException failure) { throw failure; }
        catch (Exception failure) { throw unavailable(); }
    }

    @Override
    public void revalidate(EffectiveAgentDefinition snapshot, ExecutionWorkspace workspace) {
        if (!snapshot.resources().equals(resources(workspace))) throw new ConflictException("AGENT_RESOURCE_CHANGED",
                "Agent resources changed; this session requires compatibility review.");
    }

    @Override
    public Map<String, String> resources(ExecutionWorkspace workspace) {
        var context = context(null, workspace);
        try {
            JsonNode result = reader.query(context, null, true);
            if (!result.path("diagnostics").isEmpty() || !result.path("manifest").isObject()) throw unavailable();
            var manifest = new TreeMap<String, String>();
            var fields = result.path("manifest").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                if (!entry.getValue().isTextual()) throw new IOException();
                String value = entry.getValue().textValue();
                String identity = key;
                if (key.startsWith("@project:") || key.startsWith("@global:")) {
                    identity = key.substring(key.indexOf(':') + 1);
                    if (!"directory".equals(value)) throw new IOException();
                } else if (key.startsWith("@executable:")) {
                    identity = key.substring("@executable:".length());
                    if (!"executable".equals(value)) throw new IOException();
                } else if (!value.matches("[a-f0-9]{64}")) throw new IOException();
                if (!reader.ownsResourcePath(identity, context)) throw new IOException();
                manifest.put(key, value);
            }
            // Every legitimate manifest includes the project root marker, even for an empty workspace.
            if (!"directory".equals(manifest.get("@project:" + workspace.cwd()))) throw new IOException();
            return Map.copyOf(manifest);
        } catch (CodexAgentDefinitionReader.UnsupportedRuntimeException incompatible) {
            throw runtimeUnsupported();
        } catch (Exception unavailable) {
            throw new ConflictException("AGENT_RESOURCE_UNAVAILABLE", "Agent resources cannot be preserved safely in this workspace.");
        }
    }

    private static LibraryContext context(UUID project, ExecutionWorkspace workspace) {
        var roots = new ArrayList<Path>();
        roots.add(workspace.cwd());
        workspace.workspaceRoots().stream().filter(path -> !roots.contains(path)).forEach(roots::add);
        return new LibraryContext(project, null, workspace.cwd(), roots);
    }

    private static String requiredText(JsonNode value, String field) throws IOException {
        String text = optionalText(value, field);
        if (text == null) throw new IOException();
        return text;
    }

    private static String optionalText(JsonNode value, String field) throws IOException {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().isBlank()) throw new IOException();
        return node.textValue();
    }

    private static ConflictException runtimeUnsupported() {
        return new ConflictException("AGENT_DEFINITION_RUNTIME_UNSUPPORTED", "Native definition runtime is not compatible with the verified parser contract.");
    }

    private static ConflictException unavailable() {
        return new ConflictException("AGENT_DEFINITION_UNAVAILABLE", "Native definition is unavailable or its runtime configuration is unsupported.");
    }
}
