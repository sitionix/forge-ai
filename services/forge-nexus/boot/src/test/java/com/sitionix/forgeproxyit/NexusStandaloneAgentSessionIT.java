package com.sitionix.forgeproxyit;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeproxyit.infra.*;
import com.sitionix.forgeit.core.test.IntegrationTest;
import com.sitionix.forgeit.mockmvc.api.PathParams;
import com.sitionix.forgeit.wiremock.api.WireMockPathParams;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import static com.sitionix.forgeit.wiremock.api.Parameter.equalTo;
@IntegrationTest(properties={"forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"})
@ContextConfiguration(classes=Application.class)
@Import(NexusProxyTestManagerImpl.class)
class NexusStandaloneAgentSessionIT {
 static final Path TOKEN;
 static{try{TOKEN=Files.createTempFile("forge-standalone-bff-",".token");Files.writeString(TOKEN,"s".repeat(32));Files.setAttribute(TOKEN,"unix:mode",0600);TOKEN.toFile().deleteOnExit();}catch(Exception e){throw new IllegalStateException(e);}}
 @DynamicPropertySource static void identity(DynamicPropertyRegistry p){p.add("forge.ai-library.service-token-file",TOKEN::toString);}
 @Autowired NexusProxyTestManager manager;
 static final UUID PROJECT=UUID.fromString("11111111-1111-4111-8111-111111111111");
 @Test void typedCreateNeedsOnlyProjectAgentAndRepositories(){
  var upstream=manager.wiremock().createMapping(StandaloneSessionForgeItEndpoints.createUpstream()).pathPattern(WireMockPathParams.create().add("projectId",equalTo(PROJECT.toString()))).createDefault();
  manager.mockMvc().ping(StandaloneSessionForgeItEndpoints.createPublic()).withPathParameters(PathParams.create().add("projectId",PROJECT)).assertDefault();upstream.verify();
 }
 @Test void projectScopedListUsesTypedPages(){
  var upstream=manager.wiremock().createMapping(StandaloneSessionForgeItEndpoints.listUpstream()).pathPattern(WireMockPathParams.create().add("projectId",equalTo(PROJECT.toString()))).createDefault();
  manager.mockMvc().ping(StandaloneSessionForgeItEndpoints.listPublic()).withPathParameters(PathParams.create().add("projectId",PROJECT)).assertDefault();upstream.verify();
 }
}
