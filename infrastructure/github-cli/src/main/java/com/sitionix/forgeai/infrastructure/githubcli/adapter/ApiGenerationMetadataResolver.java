package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ApiGenerationMetadataResolver {

    private final ApiGenerationArtifactNaming artifactNaming;

    ApiGenerationMetadataResolver(final ApiGenerationArtifactNaming artifactNaming) {
        this.artifactNaming = artifactNaming;
    }

    String resolveGenerationName(final ApiArtifactGenerationRequest request, final String metadata) {
        final String serviceCode = this.artifactNaming.serviceCode(request.expectedArtifact(), request.generationType());
        final String execution = request.generationType();
        return this.metadataEntries(metadata).stream()
                .filter(entry -> execution.equals(entry.execution()))
                .filter(entry -> entry.definitionPath().contains("/" + serviceCode + "/rest"))
                .findFirst()
                .map(MetadataEntry::name)
                .orElseThrow(() -> new IllegalStateException("No /generate metadata entry found for expectedArtifact="
                        + request.expectedArtifact() + ", execution=" + execution + ", serviceCode=" + serviceCode));
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
