package com.sitionix.forgeagent.api.llm;

import com.fasterxml.jackson.annotation.*;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.LlmAuthorizationPort;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(LlmAuthorizationController.PREFIX)
public class LlmAuthorizationController {
    public static final String PREFIX="/api/v1/integrations/llm";
    private final LlmAuthorizationPort authorization;
    public LlmAuthorizationController(LlmAuthorizationPort authorization){this.authorization=authorization;}
    @ModelAttribute public void requireGuard(HttpServletRequest request){if(!Boolean.TRUE.equals(request.getAttribute(LlmAuthorizationRequestFilter.ALLOWED)))throw new BrowserDenied();}
    public static final class BrowserDenied extends RuntimeException {public BrowserDenied(){super("LLM_BROWSER_DENIED");}}
    @GetMapping("/providers") public List<Provider> providers(){return List.of(Provider.from(authorization.readAccount()));}
    @PostMapping("/codex/login") public Login start(@RequestBody Binding body){return Login.from(authorization.startLogin(require(body)));}
    @GetMapping("/codex/logins/{id}") public Login read(@PathVariable UUID id,HttpServletRequest request){
        var values=Collections.list(request.getHeaders("X-Forge-Browser-Binding"));
        if(values.size()!=1)throw new IllegalArgumentException("INVALID_BROWSER_BINDING");
        return Login.from(authorization.readLogin(id,values.get(0)));
    }
    @DeleteMapping("/codex/logins/{id}") public Login cancel(@PathVariable UUID id,@RequestBody Binding body){return Login.from(authorization.cancelLogin(id,require(body)));}
    @PostMapping("/codex/logout") public Provider logout(@RequestBody Empty body){if(body==null)throw new IllegalArgumentException("INVALID_REQUEST");return Provider.from(authorization.logout());}
    private static String require(Binding body){if(body==null)throw new IllegalArgumentException("INVALID_BROWSER_BINDING");return body.browserBinding();}
    public record Binding(@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String browserBinding){
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("INVALID_REQUEST");}
        @Override public String toString(){return "LlmBinding[redacted]";}
    }
    public record Empty(){
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("INVALID_REQUEST");}
        @Override public String toString(){return "EmptyLlmRequest";}
    }
    public record Provider(String providerId,LlmAuthorizationState.AuthState authState,String email,String plan,LlmAuthorizationState.Availability availability,String errorCode){
        public static Provider from(LlmAuthorizationState state){return new Provider(state.providerId(),state.authState(),state.email(),state.plan(),state.availability(),state.errorCode());}
        @Override public String toString(){return "LlmProviderResponse[redacted]";}
    }
    public record Login(UUID loginId,LlmLoginAttempt.Status status,Instant expiresAt,String authUrl,String errorCode){
        public static Login from(LlmLoginAttempt login){return new Login(login.loginId(),login.status(),login.expiresAt(),login.authUrl(),login.errorCode());}
        @Override public String toString(){return "LlmLoginResponse[redacted]";}
    }
}
