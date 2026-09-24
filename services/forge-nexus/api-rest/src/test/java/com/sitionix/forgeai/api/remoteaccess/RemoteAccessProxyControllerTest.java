package com.sitionix.forgeai.api.remoteaccess;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sitionix.forgeai.domain.remoteaccess.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class RemoteAccessProxyControllerTest {
    RemoteAccessOperations operations=mock(RemoteAccessOperations.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.standaloneSetup(new RemoteAccessProxyController(operations,Mappers.getMapper(RemoteAccessProxyMapper.class)))
            .setControllerAdvice(new RemoteAccessProxyErrors()).build();
    }
    @Test void mapsCapabilitiesAndLists() throws Exception {
        when(operations.capabilities()).thenReturn(new RemoteAccessModels.Capabilities(true,List.of("CONNECT"),List.of()));
        when(operations.invitations()).thenReturn(List.of());when(operations.sessions()).thenReturn(List.of());
        mvc.perform(get("/api/v1/infrastructure/agents/remote-access/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("supportedOperations[0]").value("CONNECT"));
        mvc.perform(get("/api/v1/infrastructure/agents/remote-access/invitations")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/infrastructure/agents/remote-access/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void invalidInputStopsBeforeUpstream() throws Exception {
        mvc.perform(post("/api/v1/infrastructure/agents/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":null}"))
            .andExpect(status().isBadRequest());verifyNoInteractions(operations);
    }
    @Test void preservesSafeUpstreamStatusCodeAndCorrelation() throws Exception {
        when(operations.capabilities()).thenThrow(new RemoteAccessClientException(410,"REMOTE_ACCESS_EXPIRED","Invitation expired","correlation"));
        mvc.perform(get("/api/v1/infrastructure/agents/remote-access/capabilities")).andExpect(status().isGone())
            .andExpect(jsonPath("code").value("REMOTE_ACCESS_EXPIRED")).andExpect(jsonPath("correlationId").value("correlation"));
    }
}
