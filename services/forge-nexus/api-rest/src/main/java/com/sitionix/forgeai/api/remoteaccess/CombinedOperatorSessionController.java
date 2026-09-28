package com.sitionix.forgeai.api.remoteaccess;

import com.sitionix.forgeai.api.security.OperatorSessionController.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/** Canonical Console routes delegated to the existing combined-mode session owner. */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name={"forge.mcp.enabled","forge.remote-access.enabled"},havingValue="true")
@RequestMapping("/api/v1/operator/session")
public class CombinedOperatorSessionController {
    private final RemoteAccessOperatorAuthentication authentication;

    public record SessionResponse(String csrfToken,String csrfHeader) {
        @Override public String toString() { return "SessionResponse[REDACTED]"; }
    }

    @PostMapping
    public SessionResponse login(@RequestBody LoginRequest body,HttpServletRequest request,HttpServletResponse response) {
        authentication.login(body.bootstrapSecret(),request,response);
        var repository=new HttpSessionCsrfTokenRepository();
        var token=repository.generateToken(request);
        repository.saveToken(token,request,response);
        return new SessionResponse(token.getToken(),token.getHeaderName());
    }

    @GetMapping
    public SessionResponse session(HttpServletRequest request,HttpServletResponse response) {
        authentication.localSession(request,response);
        var token=new HttpSessionCsrfTokenRepository().loadDeferredToken(request,response).get();
        return new SessionResponse(token.getToken(),token.getHeaderName());
    }

    @DeleteMapping
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session=request.getSession(false);
        if (session!=null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
