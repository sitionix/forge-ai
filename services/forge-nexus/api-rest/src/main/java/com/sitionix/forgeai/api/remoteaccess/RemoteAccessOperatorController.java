package com.sitionix.forgeai.api.remoteaccess;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
@RequestMapping("/api/v1/infrastructure/agents/remote-access/operator")
public class RemoteAccessOperatorController {
    private final RemoteAccessOperatorAuthentication authentication;
    public record LoginRequest(@NotBlank @Size(max=128) String secret) {
        @Override public String toString() { return "LoginRequest[REDACTED]"; }
    }
    public record OperatorSession(String csrfToken) {
        @Override public String toString() { return "OperatorSession[REDACTED]"; }
    }
    @PostMapping("/login") public OperatorSession login(@Valid @RequestBody LoginRequest body,HttpServletRequest request,HttpServletResponse response) {
        authentication.login(body.secret(),request,response);
        var repository=new HttpSessionCsrfTokenRepository();var token=repository.generateToken(request);
        repository.saveToken(token,request,response);return new OperatorSession(token.getToken());
    }
    @GetMapping("/session") public OperatorSession session(HttpServletRequest request,HttpServletResponse response) {
        return new OperatorSession(new HttpSessionCsrfTokenRepository().loadDeferredToken(request,response).get().getToken());
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session=request.getSession(false);if (session!=null) session.invalidate();
        SecurityContextHolder.clearContext();return ResponseEntity.noContent().build();
    }
}
