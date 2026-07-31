package com.sitionix.forgeai.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sitionix.forgeai.api.activeprofile.ActiveLlmEffortRequest;
import com.sitionix.forgeai.api.activeprofile.ActiveLlmProfileUpdateRequest;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsage;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class ActiveProfileApiMapperTest {

    private final ActiveProfileApiMapper mapper = Mappers.getMapper(ActiveProfileApiMapper.class);

    @Test
    void mapsNullEffortAndUsageNull() {
        final var response = this.mapper.toResponse(new ActiveProfile(
                1,
                new ActiveLlmProfile("ollama", "qwen2.5-coder:14b", null),
                null
        ));

        assertThat(response.revision()).isEqualTo(1);
        assertThat(response.llmProfile().providerId()).isEqualTo("ollama");
        assertThat(response.llmProfile().effort()).isNull();
        assertThat(response.usage()).isNull();
    }

    @Test
    void mapsPresentEffortAndTwoUsageWindows() {
        final var response = this.mapper.toResponse(new ActiveProfile(
                3,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high")),
                new LlmUsage(List.of(
                        new LlmUsageWindow("PRIMARY", 34, 300, Instant.parse("2026-07-31T12:00:00Z")),
                        new LlmUsageWindow("SECONDARY", 61, 10080, Instant.parse("2026-08-04T09:00:00Z"))
                ))
        ));

        assertThat(response.llmProfile().effort().effortId()).isEqualTo("high");
        assertThat(response.usage().windows()).hasSize(2);
        assertThat(response.usage().windows().get(0).kind()).isEqualTo("PRIMARY");
        assertThat(response.usage().windows().get(1).kind()).isEqualTo("SECONDARY");
        assertThat(response.usage().windows().get(1).resetAt()).isEqualTo(Instant.parse("2026-08-04T09:00:00Z"));
    }

    @Test
    void mapsUpdateRequestToDomainCommand() {
        final var command = this.mapper.toCommand(new ActiveLlmProfileUpdateRequest(
                3L,
                "codex",
                "gpt-5.6-sol",
                new ActiveLlmEffortRequest("high")
        ));

        assertThat(command.expectedRevision()).isEqualTo(3L);
        assertThat(command.providerId()).isEqualTo("codex");
        assertThat(command.modelId()).isEqualTo("gpt-5.6-sol");
        assertThat(command.effort()).isEqualTo(new LlmEffort("high"));
    }

    @Test
    void mapsUpdateResult() {
        final var response = this.mapper.toResponse(new ActiveLlmProfileUpdateResult(
                4,
                new ActiveLlmProfile("ollama", "qwen", null)
        ));

        assertThat(response.revision()).isEqualTo(4);
        assertThat(response.llmProfile().providerId()).isEqualTo("ollama");
        assertThat(response.llmProfile().effort()).isNull();
    }
}
