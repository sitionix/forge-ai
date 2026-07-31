package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindowKind;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmEffort;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsage;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindow;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindowKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeActiveProfileClientMapperTest {

    private KnowledgeActiveProfileClientMapper mapper;

    @BeforeEach
    void setUp() {
        this.mapper = new KnowledgeActiveProfileClientMapperImpl();
    }

    @Test
    void mapsNullableEffortAndUsageNull() {
        // given
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                1,
                new KnowledgeActiveLlmProfileDetails("ollama", "qwen", null),
                null
        );

        // when
        final ActiveProfile result = this.mapper.toDomain(response);

        // then
        assertThat(result.llmProfile().effort()).isNull();
        assertThat(result.usage()).isNull();
    }

    @Test
    void mapsPresentEffort() {
        // given
        final KnowledgeActiveLlmProfileResponse response = new KnowledgeActiveLlmProfileResponse(
                4,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high"))
        );

        // when
        final ActiveLlmProfileUpdateResult result = this.mapper.toDomain(response);

        // then
        assertThat(result.llmProfile().effort()).isEqualTo(new LlmEffort("high"));
    }

    @Test
    void mapsOneUsageWindow() {
        // given
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                3,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high")),
                new KnowledgeLlmUsage(List.of(new KnowledgeLlmUsageWindow(
                        KnowledgeLlmUsageWindowKind.PRIMARY,
                        34,
                        300,
                        Instant.parse("2026-07-31T12:00:00Z")
                )))
        );

        // when
        final ActiveProfile result = this.mapper.toDomain(response);

        // then
        assertThat(result.usage().windows()).hasSize(1);
        assertThat(result.usage().windows().getFirst().kind()).isEqualTo(LlmUsageWindowKind.PRIMARY);
    }

    @Test
    void mapsTwoUsageWindows() {
        // given
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                3,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high")),
                new KnowledgeLlmUsage(List.of(
                        new KnowledgeLlmUsageWindow(KnowledgeLlmUsageWindowKind.PRIMARY, 34, 300, Instant.parse("2026-07-31T12:00:00Z")),
                        new KnowledgeLlmUsageWindow(KnowledgeLlmUsageWindowKind.SECONDARY, 61, 10080, Instant.parse("2026-08-04T09:00:00Z"))
                ))
        );

        // when
        final ActiveProfile result = this.mapper.toDomain(response);

        // then
        assertThat(result.usage().windows()).hasSize(2);
        assertThat(result.usage().windows().get(1).kind()).isEqualTo(LlmUsageWindowKind.SECONDARY);
        assertThat(result.usage().windows().get(1).resetAt()).isEqualTo(Instant.parse("2026-08-04T09:00:00Z"));
    }

    @Test
    void mapsCommandToClientRequest() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        );

        // when
        final var request = this.mapper.toRequest(command);

        // then
        assertThat(request.expectedRevision()).isEqualTo(3);
        assertThat(request.providerId()).isEqualTo("codex");
        assertThat(request.modelId()).isEqualTo("gpt-5.6-sol");
        assertThat(request.effort().effortId()).isEqualTo("high");
    }
}
