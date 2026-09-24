package com.sitionix.forgeagent.api.remoteaccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sitionix.forgeagent.application.remoteaccess.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RemoteAccessControllerTest {
    @Mock RemoteAccessManagement management;
    @Mock RemoteAccessInvitations invitations;
    @Mock RemoteAccessMutualPairing pairing;
    @Mock RemoteAccessSetup setup;
    @Mock RemoteAccessPairingTokens tokens;
    @Mock RemoteAccessControlService control;
    MockMvc mvc;
    static final UUID ID=UUID.randomUUID();
    static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    @BeforeEach void prepare() {
        mvc=MockMvcBuilders.standaloneSetup(new RemoteAccessController(management,invitations,pairing,setup,new RemoteAccessApiMapper(tokens),control))
            .setControllerAdvice(new RemoteAccessErrorHandler()).build();
    }
    @Test void connectReturns201OnlyAfterBothSshDirectionsAreConfirmed() throws Exception {
        when(setup.displayName()).thenReturn("accessor");
        when(pairing.connect("secret","accessor")).thenReturn(session());
        mvc.perform(post("/api/v1/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":\"secret\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("PROVISIONING"))
            .andExpect(jsonPath("localPrivateKeyReference").doesNotExist()).andExpect(jsonPath("sessionPublicKey").doesNotExist());
        when(pairing.connect("secret","accessor")).thenReturn(session().activate(NOW));
        mvc.perform(post("/api/v1/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":\"secret\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("bridgeReady").value(false));
        when(pairing.connected(ID)).thenReturn(true);
        mvc.perform(post("/api/v1/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":\"secret\"}"))
            .andExpect(status().isCreated());
    }
    @Test void terminalConnectIsNotFakeProvisioning() throws Exception {
        when(setup.displayName()).thenReturn("accessor");
        when(pairing.connect("secret","accessor")).thenReturn(session().requestRevoke(NOW));
        mvc.perform(post("/api/v1/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":\"secret\"}"))
            .andExpect(status().isConflict());
    }
    @Test void invalidBodyNeverInvokesPairingAndNeverEchoesSecret() throws Exception {
        mvc.perform(post("/api/v1/remote-access/sessions").contentType("application/json").content("{\"pairingToken\":{\"synthetic-secret\":1}}"))
            .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-secret"))));
        verifyNoInteractions(pairing);
    }
    @Test void revokeReturns202UntilConfirmed() throws Exception {
        when(management.revoke(ID)).thenReturn(session().requestRevoke(NOW));
        mvc.perform(delete("/api/v1/remote-access/sessions/"+ID)).andExpect(status().isAccepted());
        when(management.revoke(ID)).thenReturn(session().requestRevoke(NOW).confirmRemoteRevokedAndClearFailure(NOW));
        when(management.bridgeRevoked(ID)).thenReturn(true);
        mvc.perform(delete("/api/v1/remote-access/sessions/"+ID)).andExpect(status().isOk());
    }
    @Test void unknownAndUnavailableErrorsAreSafe() throws Exception {
        when(management.get(ID)).thenThrow(new IllegalStateException("synthetic-secret"));
        mvc.perform(get("/api/v1/remote-access/sessions/"+ID)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("correlationId").isNotEmpty()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-secret"))));
    }
    @Test void secretRecordsHaveRedactedStringRepresentation() {
        assertThat(new RemoteAccessDtos.ConnectRequest("synthetic-secret").toString()).doesNotContain("synthetic-secret");
    }
    @Test void disableReturnsPendingUntilCleanupIsConfirmed() throws Exception {
        when(control.disable()).thenReturn(new RemoteAccessControlStatus(RemoteAccessSwitchStatus.DISABLING,true,1,0,"REMOTE_ACCESS_CLEANUP_PENDING"),
                new RemoteAccessControlStatus(RemoteAccessSwitchStatus.DISABLED,true,0,0,null));
        mvc.perform(post("/api/v1/remote-access/control/disable"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("DISABLING"));
        mvc.perform(post("/api/v1/remote-access/control/disable"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DISABLED"));
    }
    static RemoteAccessSession session() {
        return new RemoteAccessSession(ID,UUID.randomUUID(),RemoteAccessRole.ACCESSOR,UUID.randomUUID(),UUID.randomUUID(),"peer",
            new RemoteAccessEndpoint("127.0.0.1",2222,"forge-ssh"),"host","public","fingerprint",UUID.randomUUID(),
            RemoteAccessSessionStatus.PROVISIONING,NOW.minusSeconds(10),NOW.plusSeconds(290),null,null,null,
            RemoteAccessConnectivity.UNKNOWN,null,null,null,null,0);
    }
}
