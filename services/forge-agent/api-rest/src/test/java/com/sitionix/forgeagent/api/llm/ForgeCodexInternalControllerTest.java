package com.sitionix.forgeagent.api.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.port.ForgeCodexOperationsPort;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class ForgeCodexInternalControllerTest {
    @TempDir Path root;
    MockMvc mvc;
    ForgeCodexOperationsPort operations;
    static final String TOKEN="synthetic-installation-token-0123456789";
    static final String BODY="{\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"prompt\":\"input\",\"modelId\":\"m\",\"effortId\":null,\"responseMode\":\"text\",\"timeoutSeconds\":1}";
    @BeforeEach void setup() throws Exception {
        var token=root.resolve("service.token");Files.writeString(token,TOKEN);Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));
        operations=mock(ForgeCodexOperationsPort.class);
        mvc=standaloneSetup(new ForgeCodexInternalController(operations,new ObjectMapper()))
                .addFilters(new ForgeCodexServiceAuthFilter(token.toString())).build();
    }
    @Test void absent_wrong_token_and_browser_are_rejected() throws Exception {
        mvc.perform(get("/internal/v1/codex/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/codex/models").header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/codex/models").header("Authorization","Bearer "+TOKEN).header("Origin","http://localhost")).andExpect(status().isForbidden());
        verifyNoInteractions(operations);
    }
    @Test void unknown_fields_and_invalid_deadline_rejected() throws Exception {
        for(String body:new String[]{BODY.replace("\"prompt\"","\"tools\""),BODY.replace("Seconds\":1","Seconds\":0"),BODY.replace("Seconds\":1","Seconds\":5401")})
            mvc.perform(post("/internal/v1/codex/generations").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
    }
    @Test void oversized_utf8_prompt_rejected() throws Exception {
        mvc.perform(post("/internal/v1/codex/generations").header("Authorization","Bearer "+TOKEN).contentType("application/json")
                .content(BODY.replace("input","é".repeat(524289)))).andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
    }
    @Test void signed_out_rejection_remains_typed_and_safe()throws Exception {
        when(operations.submit(any())).thenThrow(new com.sitionix.forgeagent.domain.exception.LlmAuthorizationException("CODEX_AUTH_REQUIRED"));
        mvc.perform(post("/internal/v1/codex/generations").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODEX_AUTH_REQUIRED"));
    }
    @Test void service_credential_permissions_fail_closed()throws Exception {
        Files.setPosixFilePermissions(root.resolve("service.token"),PosixFilePermissions.fromString("rw-r--r--"));
        mvc.perform(get("/internal/v1/codex/models").header("Authorization","Bearer "+TOKEN)).andExpect(status().isUnauthorized());
        verifyNoInteractions(operations);
    }
    @Test void structured_fields_and_model_cursor_reach_typed_operations_only()throws Exception {
        when(operations.submit(any())).thenAnswer(x->((com.sitionix.forgeagent.domain.model.ForgeCodexGenerationRequest)x.getArgument(0)).requestId());
        mvc.perform(post("/internal/v1/codex/generations").header("Authorization","Bearer "+TOKEN).contentType("application/json").content(BODY.replace("\"text\"","\"json_object\"")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").value("00000000-0000-0000-0000-000000000001"));
        when(operations.models("next",100,false)).thenReturn(new ForgeCodexOperationsPort.Snapshot("0.160.0",java.util.Map.of("data",java.util.List.of(),"nextCursor","last")));
        mvc.perform(get("/internal/v1/codex/models?cursor=next&limit=100&includeHidden=false").header("Authorization","Bearer "+TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("last")).andExpect(header().string("X-Forge-Codex-Version","0.160.0"));
    }
}
