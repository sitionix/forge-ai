package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sitionix.forgeai.domain.remoteaccess.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
class RemoteAccessClientAdapterTest {
    RemoteAccessHttpClient http=mock(RemoteAccessHttpClient.class);
    RemoteAccessClientAdapter sut=new RemoteAccessClientAdapter(http,Mappers.getMapper(RemoteAccessClientMapper.class));
    @Test void mapsBothDirectionsAndPreservesPendingStatus() {
        var request=new RemoteAccessModels.ConnectRequest("secret-canary");
        var response=new RemoteAccessClientDtos.Session(UUID.randomUUID(),UUID.randomUUID(),RemoteAccessModels.Role.ACCESSOR,
            UUID.randomUUID(),UUID.randomUUID(),"peer",new RemoteAccessClientDtos.Endpoint("127.0.0.1",2222,"forge-ssh"),"fingerprint",
            RemoteAccessModels.Status.PROVISIONING,null,null,null,null,null,RemoteAccessModels.Connectivity.UNKNOWN,null,null,null,null,UUID.randomUUID(),false,false);
        when(http.connect(new RemoteAccessClientDtos.ConnectRequest("secret-canary"))).thenReturn(ResponseEntity.accepted().body(response));
        assertThat(sut.connect(request)).usingRecursiveComparison().isEqualTo(response);
    }
    @Test void invalidUpstreamLifecycleIsNotReportedAsSuccess() {
        when(http.connect(any())).thenReturn(ResponseEntity.status(201).build());
        assertThatThrownBy(() -> sut.connect(new RemoteAccessModels.ConnectRequest("secret")))
            .isInstanceOf(RemoteAccessClientException.class).hasMessage("Invalid Remote Access response");
    }
    @Test void activeForwardDirectionIsStillPendingUntilReverseDirectionIsConfirmed() {
        var pairId=UUID.randomUUID();
        var forward=new RemoteAccessClientDtos.Session(UUID.randomUUID(),pairId,RemoteAccessModels.Role.ACCESSOR,
            UUID.randomUUID(),UUID.randomUUID(),"peer",new RemoteAccessClientDtos.Endpoint("127.0.0.1",22,"forge-ssh"),"fingerprint",
            RemoteAccessModels.Status.ACTIVE,null,null,null,null,null,RemoteAccessModels.Connectivity.UNKNOWN,
            null,null,null,null,pairId,false,false);
        when(http.connect(any())).thenReturn(ResponseEntity.accepted().body(forward));
        assertThat(sut.connect(new RemoteAccessModels.ConnectRequest("secret-canary")).bridgeReady()).isFalse();
        when(http.connect(any())).thenReturn(ResponseEntity.status(201).body(forward));
        assertThatThrownBy(() -> sut.connect(new RemoteAccessModels.ConnectRequest("secret-canary")))
            .isInstanceOf(RemoteAccessClientException.class).hasMessage("Invalid Remote Access response");
    }
    @Test void rawErrorBodyDoesNotEscapeThroughException() {
        when(http.capabilities()).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST,"secret-canary", "secret-canary".getBytes(),java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(sut::capabilities).isInstanceOf(RemoteAccessClientException.class)
            .hasMessage("Invalid Remote Access response").hasNoCause();
    }
    @Test void disablePreservesPendingAndConfirmedStates() {
        var pending=new RemoteAccessClientDtos.Control(RemoteAccessModels.SwitchStatus.DISABLING,true,1,0,"REMOTE_ACCESS_CLEANUP_PENDING");
        var done=new RemoteAccessClientDtos.Control(RemoteAccessModels.SwitchStatus.DISABLED,true,0,0,null);
        when(http.disable()).thenReturn(ResponseEntity.accepted().body(pending),ResponseEntity.ok(done));
        assertThat(sut.disable().status()).isEqualTo(RemoteAccessModels.SwitchStatus.DISABLING);
        assertThat(sut.disable().status()).isEqualTo(RemoteAccessModels.SwitchStatus.DISABLED);
    }
    @Test void invalidDisableStatusCannotBeReportedAsCompleted() {
        when(http.disable()).thenReturn(ResponseEntity.ok(new RemoteAccessClientDtos.Control(
                RemoteAccessModels.SwitchStatus.DISABLING,true,1,0,"REMOTE_ACCESS_CLEANUP_PENDING")));
        assertThatThrownBy(sut::disable).isInstanceOf(RemoteAccessClientException.class)
                .hasMessage("Invalid Remote Access response");
    }
}
