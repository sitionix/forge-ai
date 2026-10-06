package com.sitionix.forgeai.api.llm;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.sitionix.forgeai.domain.usecase.ManageLlmAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ForgeAiLlmAuthorizationController.PREFIX)
public class ForgeAiLlmAuthorizationController {
    public static final String PREFIX="/api/v1/infrastructure/agents/integrations/llm";
    private final ManageLlmAuthorization service;
    public ForgeAiLlmAuthorizationController(ManageLlmAuthorization service){this.service=service;}
    @ModelAttribute public void requireGuard(HttpServletRequest request){binding(request);}
    @GetMapping("/providers") public List<LlmAuthorizationResponses.Provider> providers(){return service.providers().stream().map(LlmAuthorizationResponses.Provider::from).toList();}
    @PostMapping("/codex/login") public LlmAuthorizationResponses.Login start(@RequestBody Empty request,HttpServletRequest browser){require(request);return LlmAuthorizationResponses.Login.from(service.startLogin(binding(browser)));}
    @GetMapping("/codex/logins/{id}") public LlmAuthorizationResponses.Login read(@PathVariable UUID id,HttpServletRequest browser){return LlmAuthorizationResponses.Login.from(service.readLogin(id,binding(browser)));}
    @DeleteMapping("/codex/logins/{id}") public LlmAuthorizationResponses.Login cancel(@PathVariable UUID id,@RequestBody Empty request,HttpServletRequest browser){require(request);return LlmAuthorizationResponses.Login.from(service.cancelLogin(id,binding(browser)));}
    @PostMapping("/codex/logout") public LlmAuthorizationResponses.Provider logout(@RequestBody Empty request){require(request);return LlmAuthorizationResponses.Provider.from(service.logout());}
    private static String binding(HttpServletRequest request){Object value=request.getAttribute(LlmAuthorizationBrowserGuard.BINDING_ATTRIBUTE);if(!(value instanceof String binding))throw new LlmAuthorizationBrowserGuard.Denied();return binding;}
    private static void require(Empty request){if(request==null)throw new IllegalArgumentException("INVALID_REQUEST");}
    public record Empty(){
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("INVALID_REQUEST");}
        @Override public String toString(){return "EmptyLlmRequest";}
    }
}
