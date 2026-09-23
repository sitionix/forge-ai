package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.RemoteAccessModels;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring")
public interface RemoteAccessClientMapper {
    RemoteAccessModels.Capabilities domain(RemoteAccessClientDtos.Capabilities value);
    RemoteAccessModels.Invitation domain(RemoteAccessClientDtos.Invitation value);
    RemoteAccessModels.InvitationCreated domain(RemoteAccessClientDtos.InvitationCreated value);
    RemoteAccessModels.Session domain(RemoteAccessClientDtos.Session value);
    RemoteAccessClientDtos.InvitationRequest request(RemoteAccessModels.InvitationRequest value);
    RemoteAccessClientDtos.ConnectRequest request(RemoteAccessModels.ConnectRequest value);
}
