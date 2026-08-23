package com.sitionix.forgeai.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmEffortRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmEffortResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileDetailsResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmSelectionResponse;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveProfileResponse;
import com.sitionix.forgeai.api.activeprofile.LlmUsageResponse;
import com.sitionix.forgeai.api.activeprofile.LlmUsageWindowResponse;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmSelection;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsage;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindow;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class ActiveProfileApiMapperTest {

    private final ActiveProfileApiMapper mapper = Mappers.getMapper(ActiveProfileApiMapper.class);

    @Test
    void mapsNullEffortAndUsageNull() {
        // given
        final ActiveProfile source = new ActiveProfile(
                1,
                new ActiveLlmProfile("ollama", "qwen2.5-coder:14b", null, "Ollama", "Qwen 14B"),
                null
        );
        final ActiveProfileResponse expected = new ActiveProfileResponse(
                1,
                new ActiveLlmProfileDetailsResponse("ollama", "qwen2.5-coder:14b", null, "Ollama", "Qwen 14B"),
                null
        );

        // when
        final ActiveProfileResponse actual = this.mapper.toResponse(source);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsPresentEffortAndTwoUsageWindows() {
        // given
        final Instant firstResetAt = Instant.parse("2026-07-31T12:00:00Z");
        final Instant secondResetAt = Instant.parse("2026-08-04T09:00:00Z");
        final ActiveProfile source = new ActiveProfile(
                3,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high"), "Codex", "GPT-5.6-Sol"),
                new LlmUsage(List.of(
                        new LlmUsageWindow("PRIMARY", 34, 300, firstResetAt),
                        new LlmUsageWindow("SECONDARY", 61, 10080, secondResetAt)
                ))
        );
        final ActiveProfileResponse expected = new ActiveProfileResponse(
                3,
                new ActiveLlmProfileDetailsResponse(
                        "codex",
                        "gpt-5.6-sol",
                        new ActiveLlmEffortResponse("high"),
                        "Codex",
                        "GPT-5.6-Sol"
                ),
                new LlmUsageResponse(List.of(
                        new LlmUsageWindowResponse("PRIMARY", 34, 300, firstResetAt),
                        new LlmUsageWindowResponse("SECONDARY", 61, 10080, secondResetAt)
                ))
        );

        // when
        final ActiveProfileResponse actual = this.mapper.toResponse(source);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void mapsUpdateRequestToDomainCommand() {
        // given
        final ActiveLlmProfileUpdateRequest source = new ActiveLlmProfileUpdateRequest(
                3L,
                "codex",
                "gpt-5.6-sol",
                new ActiveLlmEffortRequest("high")
        );
        final UpdateActiveLlmProfileCommand expected = new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        );

        // when
        final UpdateActiveLlmProfileCommand actual = this.mapper.toCommand(source);

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void forwardsSemanticallyInvalidValuesAndIgnoresUnknownFields() throws Exception {
        final var request = new ObjectMapper().readValue("""
                {"expectedRevision":-1,"providerId":"","modelId":" ","unknownKnowledgeField":true}
                """, ActiveLlmProfileUpdateRequest.class);

        assertThat(this.mapper.toCommand(request))
                .isEqualTo(new UpdateActiveLlmProfileCommand(-1, "", " ", null));
    }

    @Test
    void mapsUpdateResult() {
        // given
        final ActiveLlmProfileUpdateResult source = new ActiveLlmProfileUpdateResult(
                4,
                new ActiveLlmSelection("ollama", "qwen", null)
        );
        final ActiveLlmProfileResponse expected = new ActiveLlmProfileResponse(
                4,
                new ActiveLlmSelectionResponse("ollama", "qwen", null)
        );

        // when
        final ActiveLlmProfileResponse actual = this.mapper.toResponse(source);

        // then
        assertThat(actual).isEqualTo(expected);
    }

}
