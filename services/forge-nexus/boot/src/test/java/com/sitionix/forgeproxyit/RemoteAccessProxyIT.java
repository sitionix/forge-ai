package com.sitionix.forgeproxyit;
import com.sitionix.forgeai.Application;
import com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorAuthentication;
import com.sitionix.forgeproxyit.infra.*;
import com.sitionix.forgeit.core.test.IntegrationTest;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
@IntegrationTest(properties={"server.address=127.0.0.1","forge.remote-access.enabled=true","forge.remote-access.operator-origin=http://127.0.0.1:9099",
    "forge.ai.infrastructure.agent.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.agent.connect-timeout=5s","forge.ai.infrastructure.agent.read-timeout=5s",
    "forge.ai.infrastructure.knowledge.base-url=${forge-it.wiremock.base-url}","forge.ai.infrastructure.jarvis.base-url=${forge-it.wiremock.base-url}"})
@ContextConfiguration(classes=Application.class)
@Import({NexusProxyTestManagerImpl.class,RemoteAccessProxyIT.OperatorFixture.class})
class RemoteAccessProxyIT {
    static final String ORIGIN="http://127.0.0.1:9099",CSRF="fixture-csrf";
    static final MockHttpSession SESSION=new MockHttpSession();
    static Path directory;
    @Autowired NexusProxyTestManager manager;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        directory=Files.createTempDirectory("stage6-nexus-");
        for (String name:List.of("operator","service")) {
            var file=directory.resolve(name);Files.writeString(file,(name.equals("operator")?"o":"s").repeat(43));
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            registry.add("forge.remote-access."+name+"-secret-file",file::toString);
        }
    }
    @BeforeEach void authenticateFixture() {
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("fixture-operator",null,List.of(new SimpleGrantedAuthority("ROLE_REMOTE_ACCESS_OPERATOR"))));
        SESSION.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        SESSION.setAttribute(RemoteAccessOperatorAuthentication.AUTHENTICATED_AT,System.currentTimeMillis());
        var request=new MockHttpServletRequest();request.setSession(SESSION);
        new HttpSessionCsrfTokenRepository().saveToken(new DefaultCsrfToken("X-CSRF-TOKEN","_csrf",CSRF),request,new MockHttpServletResponse());
    }
    @TestConfiguration static class OperatorFixture {
        @Bean MockMvcBuilderCustomizer remoteOperatorFixture() { return builder -> builder.defaultRequest(get("/").session(SESSION)); }
    }
    @AfterAll static void cleanup() throws Exception {
        for(String file:List.of("operator","service"))Files.deleteIfExists(directory.resolve(file));Files.deleteIfExists(directory);
    }
    @org.springframework.beans.factory.annotation.Value("${forge-it.wiremock.base-url}") String upstreamUrl;
    @Test void localValidationAndOriginRejectionMakeZeroUpstreamCalls() {
        var invalid=com.sitionix.forgeit.domain.endpoint.Endpoint.createContract(
            "/api/v1/infrastructure/agents/remote-access/sessions",com.sitionix.forgeit.domain.endpoint.HttpMethod.POST,
            com.sitionix.forgeai.api.remoteaccess.RemoteAccessProxyDtos.ConnectRequest.class,
            com.sitionix.forgeai.api.remoteaccess.RemoteAccessProxyDtos.Error.class,
            (com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault) c -> c.withRequest("remote-invalid.json").expectStatus(400));
        manager.mockMvc().ping(invalid).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.sessionsApi()).header("Host","127.0.0.1:9099").header("Origin","http://evil.test")
            .expectStatus(org.springframework.http.HttpStatus.FORBIDDEN).assertAndCreate();
        var uri=java.net.URI.create(upstreamUrl);
        new com.github.tomakehurst.wiremock.client.WireMock(uri.getHost(),uri.getPort()).verifyThat(0,
            com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={400,404,409,410,503})
    void preservesTypedUpstreamErrors(int status) {
        var upstream=com.sitionix.forgeit.domain.endpoint.Endpoint.createContract("/api/v1/remote-access/capabilities",
            com.sitionix.forgeit.domain.endpoint.HttpMethod.GET,Void.class,
            com.sitionix.forgeai.infrastructure.agentclient.remoteaccess.RemoteAccessClientDtos.Error.class,
            (com.sitionix.forgeit.domain.endpoint.wiremock.WiremockDefault) c -> c.plainUrl().responseStatus(status).responseBody("remote-error.json"));
        var mapping=manager.wiremock().createMapping(upstream).createDefault();
        var endpoint=com.sitionix.forgeit.domain.endpoint.Endpoint.createContract("/api/v1/infrastructure/agents/remote-access/capabilities",
            com.sitionix.forgeit.domain.endpoint.HttpMethod.GET,Void.class,
            com.sitionix.forgeai.api.remoteaccess.RemoteAccessProxyDtos.Error.class,
            (com.sitionix.forgeit.domain.endpoint.mockmvc.MockmvcDefault) c -> c.expectStatus(status).expectResponse("remote-error.json"));
        manager.mockMvc().ping(endpoint).header("Host","127.0.0.1:9099").assertDefault();mapping.verify();
    }
    @Test void capabilities() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.capabilitiesUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.capabilitiesApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void invitations() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.invitationsUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.invitationsApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void invite() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.inviteUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.inviteApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void cancel() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.cancelUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.cancelApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void connect() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.connectUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.connectApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void pending() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.pendingUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.pendingApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void sessions() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.sessionsUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.sessionsApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void getSession() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.getUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.getApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void check() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.checkUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.checkApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void revoke() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.revokeUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.revokeApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
    @Test void revoking() {
        var mapping=manager.wiremock().createMapping(RemoteAccessEndpoints.revokingUpstream()).createDefault();
        manager.mockMvc().ping(RemoteAccessEndpoints.revokingApi()).header("Host","127.0.0.1:9099").header("Origin",ORIGIN).header("X-CSRF-TOKEN",CSRF).assertDefault();
        mapping.verify();
    }
}
