package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisBuildView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisJobView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisRelationsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisStopView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeAnalysisSymbolsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceAnalysisView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceFactsView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceInventoryView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServiceStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeServicesStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSourcesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeStatusView;
import com.sitionix.forgeai.domain.props.ServiceConfigView;
import com.sitionix.forgeai.domain.props.ServicePropertiesProvider;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeFileEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeSourceEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper.KnowledgeSqliteMapper;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.repository.KnowledgeSqliteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class SqliteKnowledgeGateway implements KnowledgeGateway {

    private final KnowledgeSqliteRepository repository;
    private final KnowledgeSqliteMapper mapper;
    private final ServicePropertiesProvider servicePropertiesProvider;
    private final KnowledgeSourceRootResolver sourceRootResolver;
    private final KnowledgeSqliteFileScanner fileScanner;
    private final ObjectMapper objectMapper;

    @Override
    public KnowledgeStatusView status() {
        final KnowledgeInventoryBuildEntity build = this.repository.latestBuild().orElse(null);
        return this.mapper.status(build, this.repository.fileCount(null, null, null));
    }

    @Override
    public KnowledgeSourcesView sources() {
        return this.mapper.sources(this.repository.sources());
    }

    @Override
    public KnowledgeServicesStatusView servicesStatus() {
        final List<KnowledgeServiceStatusView> services = new ArrayList<>();
        for (final KnowledgeSourceEntity source : this.repository.sources()) {
            final int fileCount = this.repository.fileCount(source.getSourceId(), null, null);
            services.add(new KnowledgeServiceStatusView(
                    source.getSourceId(),
                    source.getDisplayName(),
                    source.getDisplayName(),
                    source.getGroup(),
                    source.getPath(),
                    Boolean.TRUE.equals(source.getRootExists()),
                    this.readStringList(source.getTagsJson()),
                    new KnowledgeServiceInventoryView(fileCount > 0 ? "READY" : "EMPTY", fileCount, null, null, source.getLastSeenAt()),
                    new KnowledgeServiceAnalysisView(
                            "NOT_ANALYZED",
                            fileCount,
                            0,
                            0.0,
                            0,
                            0,
                            fileCount,
                            0,
                            0,
                            0,
                            null,
                            null,
                            null
                    ),
                    new KnowledgeServiceFactsView(0, 0),
                    List.of()
            ));
        }
        return new KnowledgeServicesStatusView(services, null);
    }

    @Override
    public KnowledgeInventoryBuildResultView buildInventory(final KnowledgeInventoryBuildRequest request) {
        final String startedAt = Instant.now().toString();
        final List<KnowledgeSourceEntity> sources = new ArrayList<>();
        final List<KnowledgeFileEntity> files = new ArrayList<>();
        int skipped = 0;
        final Map<String, ServiceConfigView> services = this.servicePropertiesProvider.getServices();
        if (services != null) {
            for (final Map.Entry<String, ServiceConfigView> entry : services.entrySet()) {
                final String sourceId = entry.getKey();
                final ServiceConfigView service = entry.getValue();
                if (!this.matches(request, sourceId, service)) {
                    continue;
                }
                final Path root = this.sourceRootResolver.resolve(service.getPath());
                if (!Files.isDirectory(root)) {
                    continue;
                }
                final String indexedAt = Instant.now().toString();
                sources.add(this.source(sourceId, service, root, indexedAt));
                final KnowledgeSqliteScanResult scanResult = this.fileScanner.scan(sourceId, service.getPath(), root, indexedAt);
                files.addAll(scanResult.files());
                skipped += scanResult.skipped();
            }
        }
        final KnowledgeInventoryBuildEntity build = this.repository.replaceInventory(sources, files, skipped, startedAt, Instant.now().toString());
        return this.mapper.buildResult(build);
    }

    @Override
    public KnowledgeInventoryStatusView inventoryStatus() {
        return this.mapper.inventoryStatus(this.repository.latestBuild().orElse(null));
    }

    @Override
    public KnowledgeFilesView files(final KnowledgeFilesRequest request) {
        final int limit = request == null || request.limit() == null ? 20 : request.limit();
        final int offset = request == null || request.offset() == null ? 0 : request.offset();
        final String sourceId = request == null ? null : request.sourceId();
        final String pathContains = request == null ? null : request.pathContains();
        final String extension = request == null ? null : request.extension();
        return this.mapper.files(
                this.repository.files(sourceId, pathContains, extension, limit, offset),
                limit,
                offset,
                this.repository.fileCount(sourceId, pathContains, extension)
        );
    }

    @Override
    public KnowledgeAnalysisBuildView buildAnalysis(final KnowledgeAnalysisBuildRequest request) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisJobView analysisJob(final String jobId) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisStopView stopAnalysis(final String jobId) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisStatusView analysisStatus() {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisFilesView analysisFiles(final KnowledgeAnalysisFilesRequest request) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisSymbolsView analysisSymbols(final KnowledgeAnalysisSymbolsRequest request) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    @Override
    public KnowledgeAnalysisRelationsView analysisRelations(final KnowledgeAnalysisRelationsRequest request) {
        throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "AI structural analysis requires the Knowledge service HTTP adapter");
    }

    private boolean matches(final KnowledgeInventoryBuildRequest request,
                            final String sourceId,
                            final ServiceConfigView service) {
        final List<String> sourceIds = request == null || request.sourceIds() == null ? List.of() : request.sourceIds();
        final List<String> groups = request == null || request.groups() == null ? List.of() : request.groups();
        if (!sourceIds.isEmpty() && !sourceIds.contains(sourceId)) {
            return false;
        }
        if (groups.isEmpty()) {
            return true;
        }
        final String group = service.getGroup() == null ? "" : service.getGroup().name().toLowerCase();
        return groups.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(group));
    }

    private KnowledgeSourceEntity source(final String sourceId,
                                         final ServiceConfigView service,
                                         final Path root,
                                         final String indexedAt) {
        return new KnowledgeSourceEntity(
                sourceId,
                service.getLabel() == null || service.getLabel().isBlank() ? sourceId : service.getLabel(),
                service.getGroup() == null ? null : service.getGroup().name().toLowerCase(),
                service.getPath(),
                true,
                this.json(service.getTags() == null ? List.of() : service.getTags()),
                this.json(this.metadata(sourceId, service, root)),
                indexedAt
        );
    }

    private KnowledgeSourceMetadata metadata(final String sourceId, final ServiceConfigView service, final Path root) {
        return KnowledgeSourceMetadata.builder()
                .sourceId(sourceId)
                .displayName(service.getLabel())
                .group(service.getGroup() == null ? null : service.getGroup().name().toLowerCase())
                .path(service.getPath())
                .rootExists(true)
                .tags(service.getTags() == null ? List.of() : service.getTags())
                .domainKeywords(service.getDomainKeywords() == null ? List.of() : service.getDomainKeywords())
                .ownsBusinessAreas(service.getOwnsBusinessAreas() == null ? List.of() : service.getOwnsBusinessAreas())
                .absoluteRoot(root.toString())
                .build();
    }

    private String json(final Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.KNOWLEDGE_BAD_RESPONSE, "Failed to serialize Knowledge SQLite metadata", exception);
        }
    }

    private List<String> readStringList(final String value) {
        try {
            final var node = this.objectMapper.readTree(value == null || value.isBlank() ? "[]" : value);
            if (!node.isArray()) {
                return List.of();
            }
            final List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    values.add(item.asText());
                }
            });
            return values;
        } catch (final JsonProcessingException exception) {
            return List.of();
        }
    }
}
