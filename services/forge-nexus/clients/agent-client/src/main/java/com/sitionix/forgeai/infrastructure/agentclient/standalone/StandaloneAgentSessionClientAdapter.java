package com.sitionix.forgeai.infrastructure.agentclient.standalone;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
import com.sitionix.forgeai.domain.port.StandaloneAgentSessionClient;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientCallExecutor;
import static com.sitionix.forgeai.infrastructure.agentclient.standalone.StandaloneAgentSessionClientMapper.*;
import org.springframework.stereotype.Component;
@Component
public class StandaloneAgentSessionClientAdapter implements StandaloneAgentSessionClient {
 private final StandaloneAgentSessionHttpClient client;private final ForgeAgentClientCallExecutor calls;
 public StandaloneAgentSessionClientAdapter(StandaloneAgentSessionHttpClient client,ForgeAgentClientCallExecutor calls){this.client=client;this.calls=calls;}
 public Preview preview(UUID p,PreviewRequest r,ProviderDefaults fallback){return map(calls.execute(()->client.preview(p,new StandaloneSessionClientDtos.PreviewInternal(r.agentSource(),r.agentId(),r.repositoryIds(),request(fallback)))));}
 public Receipt create(UUID p,Create r,ProviderDefaults fallback){return map(calls.execute(()->client.create(p,new StandaloneSessionClientDtos.CreateInternal(request(r),request(fallback)))));}
 public SessionPage list(UUID p,String q,String before,int limit){return map(calls.execute(()->client.list(p,q,before,limit)));}
 public Details get(UUID p,UUID s){return map(calls.execute(()->client.get(p,s)));}
 public MessagePage messages(UUID p,UUID s,long after,int limit){return map(calls.execute(()->client.messages(p,s,after,limit)));}
 public EventPage events(UUID p,UUID s,long after,int limit){return map(calls.execute(()->client.events(p,s,after,limit)));}
 public Receipt send(UUID p,UUID s,Send r){return map(calls.execute(()->client.send(p,s,request(r))));}
 public Receipt cancel(UUID p,UUID s,Cancel r){return map(calls.execute(()->client.cancel(p,s,request(r))));}
}
