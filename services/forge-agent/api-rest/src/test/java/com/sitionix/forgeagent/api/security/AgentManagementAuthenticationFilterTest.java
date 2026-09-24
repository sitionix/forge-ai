package com.sitionix.forgeagent.api.security;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class AgentManagementAuthenticationFilterTest {
    @TempDir Path directory;

    @Test void requiresExactServiceBearerBeforeExistingControlAndEncodedPaths() throws Exception {
        byte[] secret = new byte[32]; Arrays.fill(secret,(byte)21);
        Path file = directory.resolve("service");
        Files.writeString(file,Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var filter = new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file));
        for (String path : List.of("/api/v1/projects","/api%2fv1/projects","/api/v1;ignored/projects","/api/v1/projects/x/logs/stream")) {
            var request = new MockHttpServletRequest("GET",path); request.setServletPath(path);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
        var request = new MockHttpServletRequest("GET","/api/v1/projects"); request.setServletPath("/api/v1/projects");
        request.addHeader("Authorization","Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        var chain = new MockFilterChain();
        filter.doFilter(request,new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isSameAs(request);
        var shell = new MockHttpServletRequest("GET","/index.html"); shell.setServletPath("/index.html");
        var shellChain = new MockFilterChain();
        filter.doFilter(shell,new MockHttpServletResponse(),shellChain);
        assertThat(shellChain.getRequest()).isSameAs(shell);
    }

    @Test void wrongRuntimeOrBootstrapBearerCannotReachApplication() throws Exception {
        byte[] secret = new byte[32]; Arrays.fill(secret,(byte)3);
        Path file = directory.resolve("service"); Files.writeString(file,Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var filter = new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file));
        for (String candidate : List.of("Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
                "Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(secret)+"x","Basic abc")) {
            var request = new MockHttpServletRequest("POST","/api/v1/projects"); request.setServletPath("/api/v1/projects");
            request.addHeader("Authorization",candidate);
            var chain = new MockFilterChain(); var response = new MockHttpServletResponse();
            filter.doFilter(request,response,chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }
    @Test void combinedOwnershipRechecksEveryDispatchAndAlias() throws Exception {
        byte[] raw=new byte[32]; Arrays.fill(raw,(byte)7);
        String general=Base64.getUrlEncoder().withoutPadding().encodeToString(raw), remote="r".repeat(43);
        Path file=directory.resolve("general"), ra=directory.resolve("remote");
        Files.writeString(file,general);Files.writeString(ra,remote);
        for (Path path:List.of(file,ra)) Files.setPosixFilePermissions(path,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var owner=new com.sitionix.forgeagent.api.remoteaccess.RemoteAccessServiceFilter(ra);
        var filter=new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file),true);
        for (var dispatch:jakarta.servlet.DispatcherType.values()) {
            for (String path:List.of("/api/v1/remote-access/sessions","/api/v1/%72emote-access/sessions","/api/v1/remote-access;v=1/sessions","/api/v1/projects")) {
                for (String token:List.of(general,remote,"wrong","")) {
                    var request=new MockHttpServletRequest("POST","/ctx"+path);request.setContextPath("/ctx");request.setRemoteAddr("127.0.0.1");request.setDispatcherType(dispatch);
                    if (dispatch==jakarta.servlet.DispatcherType.INCLUDE) request.setAttribute(jakarta.servlet.RequestDispatcher.INCLUDE_REQUEST_URI,"/ctx"+path);
                    if (!token.isEmpty()) request.addHeader("Authorization","Bearer "+token);
                    var response=new MockHttpServletResponse();var target=new MockFilterChain();
                    filter.doFilter(request,response,(req,res) -> owner.doFilter(req,res,target));
                    boolean allowed=path.endsWith("projects")?token.equals(general):token.equals(remote);
                    assertThat(target.getRequest()!=null).as("%s %s",dispatch,path).isEqualTo(allowed);
                }
            }
        }
        var duplicate=new MockHttpServletRequest("GET","/api/v1/remote-access/sessions");duplicate.setRemoteAddr("127.0.0.1");
        duplicate.addHeader("Authorization",List.of("Bearer "+remote,"Bearer "+remote));
        var target=new MockFilterChain();owner.doFilter(duplicate,new MockHttpServletResponse(),target);assertThat(target.getRequest()).isNull();
        var generalDuplicate=new MockHttpServletRequest("GET","/api/v1/projects");generalDuplicate.addHeader("Authorization",List.of("Bearer "+general,"Bearer "+general));
        target=new MockFilterChain();filter.doFilter(generalDuplicate,new MockHttpServletResponse(),target);assertThat(target.getRequest()).isNull();
        var standalone=new AgentManagementAuthenticationFilter(new ProtectedCredentialFile(file));
        var raRequest=new MockHttpServletRequest("GET","/api/v1/remote-access/sessions");raRequest.addHeader("Authorization","Bearer "+remote);
        target=new MockFilterChain();standalone.doFilter(raRequest,new MockHttpServletResponse(),target);assertThat(target.getRequest()).isNull();
        raRequest.removeHeader("Authorization");raRequest.addHeader("Authorization","Bearer "+general);
        target=new MockFilterChain();standalone.doFilter(raRequest,new MockHttpServletResponse(),target);assertThat(target.getRequest()).isNotNull();
    }
}
