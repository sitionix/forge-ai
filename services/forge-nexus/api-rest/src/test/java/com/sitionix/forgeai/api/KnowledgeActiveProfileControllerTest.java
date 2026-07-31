package com.sitionix.forgeai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sitionix.forgeai.domain.exception.KnowledgeActiveProfileClientException;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfile;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveLlmProfileUpdateResult;
import com.sitionix.forgeai.domain.model.activeprofile.ActiveProfile;
import com.sitionix.forgeai.domain.model.activeprofile.LlmEffort;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsage;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindow;
import com.sitionix.forgeai.domain.model.activeprofile.LlmUsageWindowKind;
import com.sitionix.forgeai.domain.model.activeprofile.UpdateActiveLlmProfileCommand;
import com.sitionix.forgeai.domain.usecase.GetActiveProfile;
import com.sitionix.forgeai.domain.usecase.UpdateActiveLlmProfile;
import com.sitionix.forgeai.mapper.ActiveProfileApiMapperImpl;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeActiveProfileControllerTest {

    private final GetActiveProfile getActiveProfile = mock(GetActiveProfile.class);
    private final UpdateActiveLlmProfile updateActiveLlmProfile = mock(UpdateActiveLlmProfile.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeActiveProfileController(
                    this.getActiveProfile,
                    this.updateActiveLlmProfile,
                    new ActiveProfileApiMapperImpl()
            ))
            .setControllerAdvice(new KnowledgeActiveProfileExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
            .build();

    @Test
    void getInvokesUseCaseAndMapsTypedResponse() throws Exception {
        when(this.getActiveProfile.execute()).thenReturn(new ActiveProfile(
                3,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high")),
                new LlmUsage(List.of(new LlmUsageWindow(
                        LlmUsageWindowKind.PRIMARY,
                        34,
                        300,
                        Instant.parse("2026-07-31T12:00:00Z")
                )))
        ));

        this.mockMvc.perform(get("/api/v1/infrastructure/knowledge/active-profile"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.llmProfile.providerId").value("codex"))
                .andExpect(jsonPath("$.llmProfile.modelId").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.llmProfile.effort.effortId").value("high"))
                .andExpect(jsonPath("$.usage.windows[0].kind").value("PRIMARY"))
                .andExpect(jsonPath("$.usage.windows[0].usedPercent").value(34))
                .andExpect(jsonPath("$.usage.windows[0].windowDurationMinutes").value(300))
                .andExpect(jsonPath("$.usage.windows[0].resetAt").value("2026-07-31T12:00:00Z"));

        verify(this.getActiveProfile).execute();
    }

    @Test
    void putValidatesMapsAndInvokesUseCase() throws Exception {
        when(this.updateActiveLlmProfile.execute(new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        ))).thenReturn(new ActiveLlmProfileUpdateResult(
                4,
                new ActiveLlmProfile("codex", "gpt-5.6-sol", new LlmEffort("high"))
        ));

        this.mockMvc.perform(put("/api/v1/infrastructure/knowledge/active-profile/llm-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":3,"providerId":"codex","modelId":"gpt-5.6-sol","effort":{"effortId":"high"}}
                                """.strip()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.llmProfile.providerId").value("codex"))
                .andExpect(jsonPath("$.llmProfile.modelId").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.llmProfile.effort.effortId").value("high"));

        final ArgumentCaptor<UpdateActiveLlmProfileCommand> command = ArgumentCaptor.forClass(UpdateActiveLlmProfileCommand.class);
        verify(this.updateActiveLlmProfile).execute(command.capture());
        assertThat(command.getValue()).isEqualTo(new UpdateActiveLlmProfileCommand(
                3,
                "codex",
                "gpt-5.6-sol",
                new LlmEffort("high")
        ));
    }

    @Test
    void invalidPutRequestReturnsBadRequestBeforeUseCase() throws Exception {
        this.mockMvc.perform(put("/api/v1/infrastructure/knowledge/active-profile/llm-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":0,"providerId":"","modelId":"gpt-5.6-sol","effort":null}
                                """.strip()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.updateActiveLlmProfile);
    }

    @Test
    void unknownPutRequestFieldReturnsBadRequestBeforeUseCase() throws Exception {
        this.mockMvc.perform(put("/api/v1/infrastructure/knowledge/active-profile/llm-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":3,"providerId":"codex","modelId":"gpt-5.6-sol","effort":null,"metadata":{}}
                                """.strip()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(this.updateActiveLlmProfile);
    }

    @Test
    void controlledKnowledgeErrorIsPreserved() throws Exception {
        when(this.updateActiveLlmProfile.execute(new UpdateActiveLlmProfileCommand(3, "ollama", "qwen", null)))
                .thenThrow(new KnowledgeActiveProfileClientException(
                        409,
                        "ACTIVE_PROFILE_REVISION_CONFLICT",
                        "The active profile was changed by another request",
                        "corr-409"
                ));

        this.mockMvc.perform(put("/api/v1/infrastructure/knowledge/active-profile/llm-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":3,"providerId":"ollama","modelId":"qwen","effort":null}
                                """.strip()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PROFILE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("The active profile was changed by another request"))
                .andExpect(jsonPath("$.correlationId").value("corr-409"));
    }

    @Test
    void controllerMethodsUseConcreteResponseTypesAndNoByteArrays() {
        for (final Method method : KnowledgeActiveProfileController.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.getName().startsWith("$jacoco")) {
                continue;
            }
            assertThat(method.getReturnType()).isEqualTo(ResponseEntity.class);
            assertThat(Arrays.stream(method.getParameterTypes()))
                    .noneMatch(byte[].class::equals);
        }
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
