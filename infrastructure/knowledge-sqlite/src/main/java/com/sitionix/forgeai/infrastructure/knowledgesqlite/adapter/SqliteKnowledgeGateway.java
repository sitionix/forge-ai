package com.sitionix.forgeai.infrastructure.knowledgesqlite.adapter;

import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextBudgetView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextItemView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextRequest;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextSourceView;
import com.sitionix.forgeai.application.infrastructure.knowledge.KnowledgeContextView;
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
import com.sitionix.forgeai.infrastructure.knowledgesqlite.entity.KnowledgeInventoryBuildEntity;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.mapper.KnowledgeSqliteMapper;
import com.sitionix.forgeai.infrastructure.knowledgesqlite.repository.KnowledgeSqliteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "forge.ai.infrastructure.knowledge.mode", havingValue = "sqlite")
public class SqliteKnowledgeGateway implements KnowledgeGateway {

    private final KnowledgeSqliteRepository repository;
    private final KnowledgeSqliteMapper mapper;

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
        return this.mapper.buildResult(this.repository.latestBuild().orElse(null));
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
}
