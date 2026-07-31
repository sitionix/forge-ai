package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsage;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindow;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmEffort;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileDetails;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileRequest;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveLlmProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeActiveProfileResponse;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsage;
import com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile.dto.KnowledgeLlmUsageWindow;
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
    void mapsActiveProfileWithNullUsage() {
        // given
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                1L,
                new KnowledgeActiveLlmProfileDetails("ollama", "qwen", null),
                null
        );
        final ActiveProfile expected = new ActiveProfile(
                1,
                new ActiveLlmProfile("ollama", "qwen", null),
                null
        );

        // when
        final ActiveProfile actual = this.mapper.toDomain(response);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsActiveProfileWithEffortAndUsageWindows() {
        // given
        final Instant firstResetAt = Instant.parse("2026-07-31T12:00:00Z");
        final Instant secondResetAt = Instant.parse("2026-08-04T09:00:00Z");
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                3L,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high")),
                new KnowledgeLlmUsage(List.of(
                        new KnowledgeLlmUsageWindow("PRIMARY", 34, 300, firstResetAt),
                        new KnowledgeLlmUsageWindow("SECONDARY", 61, 10080, secondResetAt)
                ))
        );
        final ActiveProfile expected = new ActiveProfile(
                3,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high")),
                new LlmUsage(List.of(
                        new LlmUsageWindow("PRIMARY", 34, 300, firstResetAt),
                        new LlmUsageWindow("SECONDARY", 61, 10080, secondResetAt)
                ))
        );

        // when
        final ActiveProfile actual = this.mapper.toDomain(response);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsUpdateResponse() {
        // given
        final KnowledgeActiveLlmProfileResponse response = new KnowledgeActiveLlmProfileResponse(
                4L,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high"))
        );
        final ActiveLlmProfileUpdateResult expected = new ActiveLlmProfileUpdateResult(
                4,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high"))
        );

        // when
        final ActiveLlmProfileUpdateResult actual = this.mapper.toDomain(response);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsClientRequest() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        );
        final KnowledgeActiveLlmProfileRequest expected = new KnowledgeActiveLlmProfileRequest(
                3,
                "codex",
                "gpt-5.6-sol",
                new KnowledgeActiveLlmEffort("high")
        );

        // when
        final KnowledgeActiveLlmProfileRequest actual = this.mapper.toRequest(command);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsClientRequestWithNullEffort() {
        // given
        final UpdateActiveLlmProfileCommand command = new UpdateActiveLlmProfileCommand(
                1,
                "ollama",
                "qwen-model",
                null
        );
        final KnowledgeActiveLlmProfileRequest expected = new KnowledgeActiveLlmProfileRequest(
                1,
                "ollama",
                "qwen-model",
                null
        );

        // when
        final KnowledgeActiveLlmProfileRequest actual = this.mapper.toRequest(command);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsClientResponse() {
        // given
        final KnowledgeActiveProfileResponse response = new KnowledgeActiveProfileResponse(
                3L,
                new KnowledgeActiveLlmProfileDetails("codex", "gpt-5.6-sol", new KnowledgeActiveLlmEffort("high")),
                null
        );
        final ActiveProfile expected = new ActiveProfile(
                3,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high")),
                null
        );

        // when
        final ActiveProfile actual = this.mapper.toDomain(response);

        // then
        assertThat(actual).isEqualTo(expected);
    }
}
