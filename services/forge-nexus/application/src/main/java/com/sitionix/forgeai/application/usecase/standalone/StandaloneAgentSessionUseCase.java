package com.sitionix.forgeai.application.usecase.standalone;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.standalone.StandaloneSessionContracts.*;
import com.sitionix.forgeai.domain.port.*;
import com.sitionix.forgeai.domain.usecase.StandaloneAgentSessionUseCases;
import org.springframework.stereotype.Service;
@Service
public class StandaloneAgentSessionUseCase implements StandaloneAgentSessionUseCases {
 private final StandaloneAgentSessionClient client;private final KnowledgeActiveProfileClient knowledge;
 public StandaloneAgentSessionUseCase(StandaloneAgentSessionClient client,KnowledgeActiveProfileClient knowledge){this.client=client;this.knowledge=knowledge;}
 private ProviderDefaults defaults(String source){
  if(!"NATIVE_CODEX".equals(source))return null;
  try{var active=knowledge.getActiveProfile();if(active==null||active.llmProfile()==null)return null;var p=active.llmProfile();return new ProviderDefaults(p.providerId(),p.modelId(),p.effort()==null?null:p.effort().effortId());}
  catch(RuntimeException unavailable){return null;}
 }
 public Preview preview(UUID project,PreviewRequest r){return client.preview(project,r,defaults(r.agentSource()));}
 public Receipt create(UUID project,Create r){return client.create(project,r,defaults(r.agentSource()));}
 public SessionPage list(UUID p,String q,String before,int limit){return client.list(p,q,before,limit);}
 public Details get(UUID p,UUID s){return client.get(p,s);}
 public MessagePage messages(UUID p,UUID s,long after,int limit){return client.messages(p,s,after,limit);}
 public EventPage events(UUID p,UUID s,long after,int limit){return client.events(p,s,after,limit);}
 public Receipt send(UUID p,UUID s,Send r){return client.send(p,s,r);}
 public Receipt cancel(UUID p,UUID s,Cancel r){return client.cancel(p,s,r);}
}
