package com.sitionix.forgeai.infrastructure.agentclient.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeai.domain.model.library.*;
import com.sitionix.forgeai.domain.port.*;
import com.sitionix.forgeai.domain.exception.AgentClientException;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import com.sitionix.forgeai.infrastructure.agentclient.remoteaccess.RemoteAccessSecretFile;
import org.springframework.stereotype.Component;

/** Dedicated authenticated, bounded query. Browser headers never enter this client. */
@Component
public final class AiLibraryClientAdapter implements AiLibraryClient {
 private final ForgeAgentClientProperties properties;private final ObjectMapper json;private final ForgeAgentClient projects;private final String secretFile;private final boolean legacyIdentity;
 @Autowired
 public AiLibraryClientAdapter(ForgeAgentClientProperties properties,ObjectMapper json,ForgeAgentClient projects,Environment environment) {
  this(properties,json,projects,environment.resolveRequiredPlaceholders("${forge.ai-library.service-token-file:${forge.remote-access.service-secret-file:${FORGE_CODEX_SERVICE_TOKEN_FILE:/etc/forge-ai/codex-service/token}}}"),
    !environment.containsProperty("forge.ai-library.service-token-file") && environment.containsProperty("forge.remote-access.service-secret-file"));
 }
 AiLibraryClientAdapter(ForgeAgentClientProperties properties,ObjectMapper json,ForgeAgentClient projects,String secretFile) { this(properties,json,projects,secretFile,false); }
 private AiLibraryClientAdapter(ForgeAgentClientProperties properties,ObjectMapper json,ForgeAgentClient projects,String secretFile,boolean legacyIdentity) {
  this.properties=properties;this.json=json;this.projects=projects;this.secretFile=secretFile;this.legacyIdentity=legacyIdentity;
 }
 public record QueryRequest(LibraryProviderContext provider) {}
 @Override public LibrarySnapshot query(UUID projectId,LibraryProviderContext provider) {
  try {
   URI base=properties.getBaseUrl();
   if(!properties.enabled()||base==null||!"http".equals(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
       || !Arrays.stream(InetAddress.getAllByName(base.getHost())).allMatch(InetAddress::isLoopbackAddress)||secretFile.isBlank())return unavailable(projectId,provider);
   String token=legacyIdentity?RemoteAccessSecretFile.read(Path.of(secretFile)):ServiceTokenFile.read(Path.of(secretFile));
   final LibrarySnapshot snapshot;
   try(var transport=new AiLibraryHttpTransport(json,Duration.ofSeconds(45),8*1024*1024)) {
    URI endpoint=base.resolve("/api/v1/projects/"+projectId+"/ai-library/query");
    snapshot=transport.query(endpoint,token,new QueryRequest(provider));
   }
   if(snapshot==null||!projectId.equals(snapshot.projectId())||!Objects.equals(provider,snapshot.provider())||snapshot.categories()==null)
    throw new IllegalStateException("Invalid inventory context");
   return snapshot;
  }catch(AgentClientException failure){throw failure;}
  catch(Exception unavailable){return unavailable(projectId,provider);}
 }
 private LibrarySnapshot unavailable(UUID id,LibraryProviderContext provider){
  if(projects.listProjects().stream().noneMatch(p->id.equals(p.id())))throw new AgentClientException(404,"{\"code\":\"PROJECT_NOT_FOUND\",\"message\":\"Project was not found.\"}",Map.of(),null);
  return new LibrarySnapshot(id,provider==null?null:provider.profileRevision(),provider,LibrarySnapshot.Categories.unavailable());
 }
}
