package com.sitionix.forgeproxyit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sitionix.forgeai.Application;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real Nexus boot, filters, serialization and Agent HTTP client against a synthetic HTTP provider. */
@SpringBootTest(classes=Application.class, properties={"spring.docker.compose.enabled=false", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration", "logging.level.org.springframework.web=TRACE"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class NexusLlmAuthorizationIT {
    static final String BASE="/api/v1/infrastructure/agents/integrations/llm";
    static final String ORIGIN="http://127.0.0.1:9099";
    static final String ID="88888888-8888-4888-8888-888888888888";
    static final String ACCOUNT="[{\"providerId\":\"codex\",\"authState\":\"SIGNED_OUT\",\"email\":null,\"plan\":null,\"availability\":\"AVAILABLE\",\"errorCode\":null}]";
    static final String ATTEMPT="{\"loginId\":\""+ID+"\",\"status\":\"PENDING\",\"expiresAt\":\"2026-10-04T00:00:00Z\",\"authUrl\":\"https://auth.openai.com/authorize?state=url-canary\",\"errorCode\":null}";
    static final AtomicReference<String> body=new AtomicReference<>(ACCOUNT), received=new AtomicReference<>();
    static final AtomicInteger calls=new AtomicInteger(), status=new AtomicInteger(200);
    static final AtomicBoolean disconnected=new AtomicBoolean();
    static final HttpServer agent;
    static {
        try {
            agent=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            agent.createContext("/",exchange->{
                calls.incrementAndGet();
                if(disconnected.get()){exchange.close();return;}
                received.set(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)+" "+exchange.getRequestHeaders().getFirst("X-Forge-Browser-Binding"));
                var bytes=body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });agent.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void upstream(DynamicPropertyRegistry registry){registry.add("forge.ai.infrastructure.agent.base-url",()->"http://127.0.0.1:"+agent.getAddress().getPort());}
    @AfterAll static void stop(){agent.stop(0);}
    @BeforeEach void reset(){body.set(ACCOUNT);status.set(200);calls.set(0);disconnected.set(false);}
    @Autowired MockMvc mvc;
    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request){return request.with(r->{r.setServerName("127.0.0.1");r.setServerPort(9099);return r;}).header("Host","127.0.0.1:9099");}
    private Cookie bootstrap() throws Exception {
        var result=mvc.perform(local(get(BASE+"/providers")).header("Sec-Fetch-Site","same-origin"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$[0].authState").value("SIGNED_OUT")).andReturn();
        var cookie=result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly","SameSite=Lax","Path=/fgaisox" );
        return new Cookie("ForgeLlmBrowser",cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';')));
    }
    @Test void firstVisitAndReloadBootstrapOnlyWithProvenSameOrigin() throws Exception {
        var first=bootstrap();var reloaded=bootstrap();assertThat(first.getValue()).isNotEqualTo(reloaded.getValue());
        mvc.perform(local(get(BASE+"/providers")).cookie(first)).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(local(get(BASE+"/providers"))).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void originFetchMetadataAndHostGuardsRejectBeforeUpstream() throws Exception {
        mvc.perform(local(post(BASE+"/codex/login")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(local(post(BASE+"/codex/login")).header("Origin",ORIGIN,"http://evil.example").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(local(post(BASE+"/codex/logout")).header("Origin",ORIGIN).header("Sec-Fetch-Site","cross-site").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/providers").header("Origin",ORIGIN).header("Host","evil.example")).andExpect(status().isForbidden());
        mvc.perform(local(get(BASE+"/providers")).header("Origin","http://evil.example").header("Sec-Fetch-Site","same-origin")).andExpect(status().isForbidden());
        assertThat(calls.get()).isZero();
    }
    @Test void bindingIsServerIssuedBodiesEmptyAndDuplicatesKeepOwnership(CapturedOutput output) throws Exception {
        var owner=bootstrap();body.set(ATTEMPT);
        for(int i=0;i<2;i++)mvc.perform(local(post(BASE+"/codex/login")).header("Origin",ORIGIN).cookie(owner).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value(ID)).andExpect(header().string("Cache-Control","no-store"));
        assertThat(received.get()).contains("POST /api/v1/integrations/llm/codex/login","\"browserBinding\":\""+owner.getValue());
        mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner)).andExpect(status().isOk());
        assertThat(received.get()).contains("GET /api/v1/integrations/llm/codex/logins/"+ID,owner.getValue()).doesNotContain("?");
        mvc.perform(local(delete(BASE+"/codex/logins/"+ID)).header("Origin",ORIGIN).cookie(owner).contentType("application/json").content("{}" )).andExpect(status().isOk());
        mvc.perform(local(get(BASE+"/codex/logins/"+ID))).andExpect(status().isForbidden());
        mvc.perform(local(post(BASE+"/codex/login")).header("Origin",ORIGIN).cookie(new Cookie("ForgeLlmBrowser","client-chosen-canary")).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(local(post(BASE+"/codex/login")).header("Origin",ORIGIN).cookie(owner).contentType("application/json").content("{\"browserBinding\":\"client-chosen-canary\"}" )).andExpect(status().isBadRequest());
        mvc.perform(local(post(BASE+"/codex/login")).header("Origin",ORIGIN).cookie(owner).contentType("text/plain").content("{}" )).andExpect(status().isForbidden());
        assertThat(output.getAll()).doesNotContain("url-canary",owner.getValue(),"client-chosen-canary");
    }
    @Test void upstreamErrorsAndMalformedSuccessNeverExposeRawDiagnostics() throws Exception {
        var owner=bootstrap();
        status.set(404);body.set("{\"code\":\"LOGIN_NOT_FOUND\",\"message\":\"raw-token-canary\"}");
        mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOGIN_NOT_FOUND")).andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("canary"))));
        status.set(503);body.set("raw-token-canary");
        mvc.perform(local(get(BASE+"/providers")).cookie(owner)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CODEX_AUTH_UNAVAILABLE"));
        status.set(200);body.set(ATTEMPT.replace("https://auth.openai.com/authorize?state=url-canary","https://evil.example/token-canary"));
        mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
        body.set("{\"raw\":\"token-canary\"}");mvc.perform(local(get(BASE+"/providers")).cookie(owner)).andExpect(status().isServiceUnavailable());
    }
    @Test void malformedJsonAndDuplicateBindingNeverLogAttackerValues(CapturedOutput output) throws Exception {
        var owner=bootstrap();int before=calls.get();
        mvc.perform(local(post(BASE+"/codex/logout")).header("Origin",ORIGIN).cookie(owner).contentType("application/json").content("malformedBodyCanary"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(local(post(BASE+"/codex/logout")).header("Origin",ORIGIN).cookie(owner,owner).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertThat(calls.get()).isEqualTo(before);assertThat(output.getAll()).doesNotContain("malformedBodyCanary",owner.getValue());
    }
    @Test void matrixPathsCannotBypassOriginAndHostChecks() throws Exception {
        mvc.perform(local(post(BASE+";v=1/codex/logout")).header("Origin","http://evil.example").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(calls.get()).isZero();
    }
    @Test void statusSafeguardsApplyEvenWithValidCookieAndForwardedHeaders() throws Exception {
        var owner=bootstrap();int before=calls.get();
        for(String path:java.util.List.of("/providers","/codex/logins/"+ID)) {
            mvc.perform(local(get(BASE+path)).cookie(owner).header("Origin","http://evil.example")).andExpect(status().isForbidden());
            mvc.perform(local(get(BASE+path)).cookie(owner).header("Sec-Fetch-Site","cross-site")).andExpect(status().isForbidden());
            mvc.perform(local(get(BASE+path)).cookie(owner).header("Origin",ORIGIN,ORIGIN)).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE+"/providers").cookie(owner).header("Host","evil.example").header("Origin",ORIGIN)
                .header("X-Forwarded-Host","127.0.0.1:9099").header("X-Forwarded-Proto","http")).andExpect(status().isForbidden());
        assertThat(calls.get()).isEqualTo(before);
    }
    @Test void networkDisconnectIsSafe503AndLogoutReturnsTypedAccount() throws Exception {
        var owner=bootstrap();disconnected.set(true);
        mvc.perform(local(get(BASE+"/providers")).cookie(owner)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CODEX_AUTH_UNAVAILABLE")).andExpect(header().string("Cache-Control","no-store"));
        disconnected.set(false);body.set(ACCOUNT.substring(1,ACCOUNT.length()-1));
        mvc.perform(local(post(BASE+"/codex/logout")).cookie(owner).header("Origin",ORIGIN).contentType("application/json; charset=UTF-8").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT")).andExpect(jsonPath("$.generation").doesNotExist());
        assertThat(received.get()).contains("POST /api/v1/integrations/llm/codex/logout {}").doesNotContain(owner.getValue());
    }
    @Test void confirmedLoginAccountAndLogoutRoundTripRetainBrowserBoundary() throws Exception {
        var owner=bootstrap();
        body.set(ATTEMPT);
        mvc.perform(local(post(BASE+"/codex/login")).cookie(owner).header("Origin",ORIGIN)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        body.set(ATTEMPT.replace("PENDING","COMPLETED"));
        mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.authUrl").isEmpty());
        body.set(ACCOUNT.replace("SIGNED_OUT","CONNECTED").replace("\"email\":null","\"email\":\"forge@example.test\""));
        mvc.perform(local(get(BASE+"/providers")).cookie(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("forge@example.test"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        int before=calls.get();
        mvc.perform(local(post(BASE+"/codex/logout")).cookie(owner).header("Origin","http://evil.example")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        assertThat(calls.get()).isEqualTo(before);
        body.set(ACCOUNT.substring(1,ACCOUNT.length()-1));
        mvc.perform(local(post(BASE+"/codex/logout")).cookie(owner).header("Origin",ORIGIN)
                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authState").value("SIGNED_OUT"));
        body.set(ACCOUNT);
        mvc.perform(local(get(BASE+"/providers")).cookie(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].email").isEmpty())
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void malformedEnumsMissingFieldsUnsafeUrlsAndTerminalUrlsAreSafe() throws Exception {
        var owner=bootstrap();
        for(String response:java.util.List.of(ATTEMPT.replace("PENDING","UNKNOWN"),ATTEMPT.replace("\"expiresAt\":\"2026-10-04T00:00:00Z\",",""),
                ATTEMPT.replace("https://auth.openai.com/authorize?state=url-canary","https://user:password@auth.openai.com/authorize"),
                ATTEMPT.replace("https://auth.openai.com/authorize?state=url-canary","https://auth.openai.com/authorize#token-canary"))) {
            body.set(response);mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CODEX_AUTH_UNAVAILABLE"));
        }
        body.set(ATTEMPT.replace("PENDING","COMPLETED"));
        mvc.perform(local(get(BASE+"/codex/logins/"+ID)).cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.authUrl").isEmpty());
        mvc.perform(local(get(BASE+"/codex/logins/not-uuid")).cookie(owner)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(local(request(org.springframework.http.HttpMethod.TRACE,BASE+"/providers")).cookie(owner)).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Cache-Control","no-store"));
    }
}
