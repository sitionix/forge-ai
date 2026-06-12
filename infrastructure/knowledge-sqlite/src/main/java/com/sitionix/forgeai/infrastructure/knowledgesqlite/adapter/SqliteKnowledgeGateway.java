package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextBudgetView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextItemView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextSourceView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeDiagnosticView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeFilesView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGateway;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayErrorCode;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeGatewayException;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryBuildResultView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeInventoryStatusView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeSearchResultView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public KnowledgeSearchResultView search(final KnowledgeSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.SEARCH_QUERY_INVALID, "Search query must not be empty");
        }
        final int limit = request.limit() == null ? 20 : request.limit();
        return this.mapper.search(
                request.query(),
                this.repository.contextFiles(request.query(), request.sourceIds(), request.groups(), limit)
        );
    }

    @Override
    public KnowledgeContextView context(final KnowledgeContextRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new KnowledgeGatewayException(KnowledgeGatewayErrorCode.CONTEXT_QUERY_INVALID, "Context query must not be empty");
        }
        final int maxChars = request.maxChars() == null ? 12000 : request.maxChars();
        if (this.repository.latestBuild().isEmpty()) {
            return new KnowledgeContextView(
                    request.query(),
                    List.of(),
                    List.of(),
                    new KnowledgeContextBudgetView(maxChars, 0, false),
                    List.of(new KnowledgeDiagnosticView("INVENTORY_EMPTY", "Inventory is empty. Build inventory first."))
            );
        }
        final int maxItems = request.maxItems() == null ? 12 : request.maxItems();
        final boolean includeContent = request.includeContent() == null || request.includeContent();
        final List<KnowledgeContextItemView> context = new ArrayList<>();
        int usedChars = 0;
        boolean truncated = false;
        for (final KnowledgeContextItemView candidate : this.repository
                .contextFiles(request.query(), request.sourceIds(), request.groups(), maxItems)
                .stream()
                .map(file -> this.mapper.contextItem(file, includeContent))
                .toList()) {
            final String content = candidate.content();
            final int contentChars = content == null ? 0 : content.length();
            if (usedChars + contentChars > maxChars) {
                truncated = true;
                break;
            }
            context.add(candidate);
            usedChars += contentChars;
        }
        return new KnowledgeContextView(
                request.query(),
                context,
                sourcesUsed(context),
                new KnowledgeContextBudgetView(maxChars, usedChars, truncated),
                List.of()
        );
    }

    private List<KnowledgeContextSourceView> sourcesUsed(final List<KnowledgeContextItemView> context) {
        final Set<String> seen = new LinkedHashSet<>();
        return context.stream()
                .filter(item -> seen.add(item.sourceId()))
                .map(this.mapper::contextSource)
                .toList();
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
}
