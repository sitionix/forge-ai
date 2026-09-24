package com.sitionix.forgeai.api.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operator/session")
@ConditionalOnProperty(name="forge.mcp.enabled",havingValue="true")
public final class OperatorSessionController {
    private final OperatorSessionService sessions;
    private final URI origin;
    public OperatorSessionController(OperatorSessionService sessions,URI operatorOrigin) {
        this.sessions=sessions; this.origin=operatorOrigin;
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest body,HttpServletRequest request) {
        try {
            var session=sessions.login(body.bootstrapSecret());
            var headers=noStore();
            headers.add(HttpHeaders.SET_COOKIE,cookie(session.id(),request,false).toString());
            return new ResponseEntity<>(new SessionResponse(session.csrf()),headers,HttpStatus.OK);
        } catch (IllegalArgumentException exception) {
            return new ResponseEntity<>(null,noStore(),HttpStatus.UNAUTHORIZED);
        }
    }
    @GetMapping
    public ResponseEntity<SessionResponse> session(HttpServletRequest request) {
        return sessions.find(OperatorManagementAuthenticationFilter.cookie(request))
                .map(session -> new ResponseEntity<>(new SessionResponse(session.csrf()),noStore(),HttpStatus.OK))
                .orElseGet(() -> new ResponseEntity<>(null,noStore(),HttpStatus.UNAUTHORIZED));
    }
    @DeleteMapping
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(OperatorManagementAuthenticationFilter.cookie(request));
        var headers=noStore();
        headers.add(HttpHeaders.SET_COOKIE,cookie("",request,true).toString());
        return new ResponseEntity<>(headers,HttpStatus.NO_CONTENT);
    }
    private ResponseCookie cookie(String value,HttpServletRequest request,boolean clear) {
        return ResponseCookie.from(OperatorManagementAuthenticationFilter.COOKIE,value)
                .httpOnly(true).secure("https".equalsIgnoreCase(origin.getScheme())).sameSite("Strict")
                .path(request.getContextPath().isEmpty()?"/":request.getContextPath())
                .maxAge(clear?java.time.Duration.ZERO:sessions.ttl()).build();
    }
    private static HttpHeaders noStore() { var headers=new HttpHeaders(); headers.setCacheControl(CacheControl.noStore()); return headers; }
    public static final class LoginRequest {
        private final String bootstrapSecret;
        @JsonCreator public LoginRequest(@JsonProperty("bootstrapSecret") String bootstrapSecret) { this.bootstrapSecret=bootstrapSecret; }
        @JsonProperty(value="bootstrapSecret",access=JsonProperty.Access.WRITE_ONLY)
        public String bootstrapSecret() { return bootstrapSecret; }
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public static final class SessionResponse {
        private final String csrfToken;
        public SessionResponse(String csrfToken) { this.csrfToken=csrfToken; }
        @JsonProperty("csrfToken") public String csrfToken() { return csrfToken; }
        @Override public String toString() { return "SessionResponse[redacted]"; }
    }
}
