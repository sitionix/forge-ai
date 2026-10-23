package com.sitionix.forgeagent.api.remoteaccess;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
class RemoteAccessServiceFilterTest {
    @TempDir Path dir;
    @Test void onlyLoopbackWithDedicatedCredentialIsAdmitted() throws Exception {
        var key=dir.resolve("key"); Files.writeString(key,"a".repeat(43));
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
        var filter=new RemoteAccessServiceFilter(key);
        for (String remote : new String[]{"127.0.0.1","192.168.1.2"}) {
            var request=new MockHttpServletRequest("GET","/api/v1/remote-access/sessions"); request.setRemoteAddr(remote);
            request.addHeader("Authorization","Bearer "+"a".repeat(43));
            var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
            filter.doFilter(request,response,chain);
            assertThat(chain.getRequest()!=null).isEqualTo(remote.equals("127.0.0.1"));
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        var request=new MockHttpServletRequest("GET","/api/v1/remote-access/sessions");
        var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(401); assertThat(chain.getRequest()).isNull();
    }
    @Test void unsafeOrSymlinkSecretFileFailsClosed() throws Exception {
        var key=dir.resolve("key"); Files.writeString(key,"a".repeat(43));
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> new RemoteAccessServiceFilter(key)).isInstanceOf(IllegalStateException.class);
        Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"));
        var link=dir.resolve("link");Files.createSymbolicLink(link,key);
        assertThatThrownBy(() -> new RemoteAccessServiceFilter(link)).isInstanceOf(IllegalStateException.class);
    }
    @Test void libraryAndRemoteAccessHaveIndependentRouteOwnership() throws Exception {
        var key=dir.resolve("remote");Files.writeString(key,"r".repeat(43));Files.setAttribute(key,"unix:mode",0600);
        var remote=new RemoteAccessServiceFilter(key);var library=new RemoteAccessServiceFilter("l".repeat(32));
        var request=new MockHttpServletRequest("POST","/api/v1/projects/"+java.util.UUID.randomUUID()+"/ai-library/query");
        request.setRemoteAddr("127.0.0.1");request.addHeader("Authorization","Bearer "+"l".repeat(32));
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        remote.doFilter(request,response,(input,output)->library.doFilter(input,output,chain));
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(com.sitionix.forgeagent.api.security.AgentManagementRoutePolicy.LIBRARY_AUTHENTICATED)).isEqualTo(Boolean.TRUE);
        var remoteRequest=new MockHttpServletRequest("GET","/api/v1/remote-access/sessions");remoteRequest.setRemoteAddr("127.0.0.1");
        remoteRequest.addHeader("Authorization","Bearer "+"r".repeat(43));var remoteChain=new MockFilterChain();
        library.doFilter(remoteRequest,new MockHttpServletResponse(),(input,output)->remote.doFilter(input,output,remoteChain));
        assertThat(remoteChain.getRequest()).isSameAs(remoteRequest);
    }
}
