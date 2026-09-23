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
}
