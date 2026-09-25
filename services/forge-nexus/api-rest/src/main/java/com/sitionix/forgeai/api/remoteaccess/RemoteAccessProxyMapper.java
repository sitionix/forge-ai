package com.sitionix.forgeai.api.remoteaccess;
import com.sitionix.forgeai.domain.remoteaccess.RemoteAccessModels;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring")
public interface RemoteAccessProxyMapper {
    RemoteAccessProxyDtos.Capabilities response(RemoteAccessModels.Capabilities value);
    RemoteAccessProxyDtos.Control response(RemoteAccessModels.Control value);
    RemoteAccessProxyDtos.Invitation response(RemoteAccessModels.Invitation value);
    RemoteAccessProxyDtos.InvitationCreated response(RemoteAccessModels.InvitationCreated value);
    RemoteAccessProxyDtos.Session response(RemoteAccessModels.Session value);
    RemoteAccessModels.InvitationRequest command(RemoteAccessProxyDtos.InvitationRequest value);
    RemoteAccessModels.ConnectRequest command(RemoteAccessProxyDtos.ConnectRequest value);
}
