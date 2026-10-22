package com.sitionix.forgeai.domain.port;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
public interface StandaloneAgentSessionClient {
    Preview preview(UUID projectId,PreviewRequest request,ProviderDefaults fallback);
    Receipt create(UUID projectId,Create request,ProviderDefaults fallback);
    SessionPage list(UUID projectId,String search,String before,int limit);
    Details get(UUID projectId,UUID sessionId);
    MessagePage messages(UUID projectId,UUID sessionId,long afterSequence,int limit);
    EventPage events(UUID projectId,UUID sessionId,long afterSequence,int limit);
    Receipt send(UUID projectId,UUID sessionId,Send request);
    Receipt cancel(UUID projectId,UUID sessionId,Cancel request);
}
