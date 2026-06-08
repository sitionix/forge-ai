package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ApiGenerationMetadataResolver {

    String resolveGenerationName(final ApiArtifactGenerationRequest request, final String metadata) {
        final String execution = request.generationType();
        return this.metadataEntries(metadata).stream()
                .filter(entry -> execution.equals(entry.execution()))
                .filter(entry -> this.matchesDefinitionPath(entry.definitionPath(), request))
                .findFirst()
                .map(MetadataEntry::name)
                .orElseThrow(() -> new IllegalStateException("No /generate metadata entry found for expectedArtifact="
                        + request.expectedArtifact() + ", execution=" + execution
                        + ", apiFamily=" + request.apiFamily() + ", serviceCode=" + request.serviceCode()));
    }

    private boolean matchesDefinitionPath(final String definitionPath, final ApiArtifactGenerationRequest request) {
        if (definitionPath == null) {
            return false;
        }
        if (request.serviceCode() != null && !request.serviceCode().isBlank()
                && definitionPath.contains("/" + request.serviceCode() + "/")) {
            return true;
        }
        return request.apiFamily() != null && !request.apiFamily().isBlank()
                && definitionPath.contains("/" + request.apiFamily() + "/");
    }

    private List<MetadataEntry> metadataEntries(final String metadata) {
        final List<MetadataEntry> entries = new ArrayList<>();
        String name = null;
        String execution = null;
        String definitionPath = null;
        for (final String rawLine : metadata.split("\\R")) {
            final String line = rawLine.trim();
            if (line.startsWith("- name:")) {
                if (name != null) {
                    entries.add(new MetadataEntry(name, execution, definitionPath));
                }
                name = line.substring("- name:".length()).trim();
                execution = null;
                definitionPath = null;
            } else if (line.startsWith("api-spec-type:")) {
                execution = line.substring("api-spec-type:".length()).trim();
            } else if (line.startsWith("definition-path:")) {
                definitionPath = line.substring("definition-path:".length()).trim();
            }
        }
        if (name != null) {
            entries.add(new MetadataEntry(name, execution, definitionPath));
        }
        return entries;
    }

    private record MetadataEntry(String name, String execution, String definitionPath) {
    }
}
