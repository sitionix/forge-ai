package com.sitionix.forgeai;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class CombinedOperatorCredentialConfigurationTest {
    @TempDir Path directory;
    Path file(String name,String value) throws Exception { var p=directory.resolve(name);Files.writeString(p,value);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));return p; }
    @Test void oneBootstrapAndDistinctAudiencesWithSanitizedConflictFailures() throws Exception {
        var sut=new CombinedOperatorCredentialConfiguration();
        var operator=file("operator","o".repeat(43));var remote=file("remote","r".repeat(43));var general=file("general","g".repeat(43));
        var origin=URI.create("http://127.0.0.1:80");
        sut.combinedOperatorCredentials(operator,remote,origin,general,null,null);
        sut.combinedOperatorCredentials(operator,remote,origin,general,operator,URI.create("http://127.0.0.1"));
        assertThatThrownBy(() -> sut.combinedOperatorCredentials(operator,remote,origin,general,remote,null)).hasMessage("Conflicting operator configuration");
        assertThatThrownBy(() -> sut.combinedOperatorCredentials(operator,remote,origin,general,null,URI.create("http://127.0.0.1:81"))).hasMessage("Conflicting operator configuration");
        for(String value:new String[]{"o".repeat(43),"r".repeat(43)}) {
            Files.writeString(general,value);
            assertThatThrownBy(() -> sut.combinedOperatorCredentials(operator,remote,origin,general,null,null)).hasMessage("Protected credentials must be distinct").hasMessageNotContaining(value);
        }
    }
    @Test void combinedCookiePathIsContextWideAndSecure() throws Exception {
        var auth=new com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorAuthentication("a".repeat(43).getBytes(),URI.create("https://127.0.0.1:9099"),java.time.Clock.systemUTC());
        for(String context:new String[]{"","/fgaisox"}) {
            var factory=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory();factory.setAddress(java.net.InetAddress.getLoopbackAddress());
            new RemoteAccessOperatorConfiguration.OperatorBind(auth,context,"none",true).customize(factory);
            assertThat(factory.getSession().getCookie().getPath()).isEqualTo(context.isEmpty()?"/":context);
            assertThat(factory.getSession().getCookie().getSecure()).isTrue();
        }
    }
}
