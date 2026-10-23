package com.sitionix.forgeproxyit.infra;
import com.sitionix.forgeit.domain.endpoint.*;
import com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault;
import com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault;
import com.sitionix.forgeai.api.standalone.StandaloneSessionDtos;
import com.sitionix.forgeai.infrastructure.agentclient.standalone.StandaloneSessionClientDtos;
public final class StandaloneSessionForgeItEndpoints {
 private StandaloneSessionForgeItEndpoints() { }
 public static Endpoint<StandaloneSessionClientDtos.CreateInternal,StandaloneSessionClientDtos.Receipt> createUpstream(){return Endpoint.createContract("/api/v1/projects/{projectId}/agent-sessions",HttpMethod.POST,StandaloneSessionClientDtos.CreateInternal.class,StandaloneSessionClientDtos.Receipt.class,(WiremockDefault)c->c.plainUrl().matchesJson("standalone-create-internal.json").responseStatus(202).responseBody("standalone-receipt.json"));}
 public static Endpoint<StandaloneSessionDtos.Create,StandaloneSessionDtos.Receipt> createPublic(){return Endpoint.createContract("/api/v1/infrastructure/agents/projects/{projectId}/agent-sessions",HttpMethod.POST,StandaloneSessionDtos.Create.class,StandaloneSessionDtos.Receipt.class,(MockmvcDefault)c->c.withRequest("standalone-create.json").expectStatus(202).expectResponse("standalone-receipt.json"));}
 public static Endpoint<Void,StandaloneSessionClientDtos.SessionPage> listUpstream(){return Endpoint.createContract("/api/v1/projects/{projectId}/agent-sessions",HttpMethod.GET,Void.class,StandaloneSessionClientDtos.SessionPage.class,(WiremockDefault)c->c.plainUrl().responseStatus(200).responseBody("standalone-list.json"));}
 public static Endpoint<Void,StandaloneSessionDtos.SessionPage> listPublic(){return Endpoint.createContract("/api/v1/infrastructure/agents/projects/{projectId}/agent-sessions",HttpMethod.GET,Void.class,StandaloneSessionDtos.SessionPage.class,(MockmvcDefault)c->c.expectStatus(200).expectResponse("standalone-list.json"));}
}
