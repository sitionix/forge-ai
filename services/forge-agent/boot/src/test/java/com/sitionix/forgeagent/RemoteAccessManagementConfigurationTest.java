package com.sitionix.forgeagent;
import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
class RemoteAccessManagementConfigurationTest {
    @Test void managementRequiresExplicitLoopbackAndUnmodifiedPeerAddress() throws Exception {
        var sut=new RemoteAccessManagementConfiguration.ManagementBind("none");
        var factory=new TomcatServletWebServerFactory();
        assertThatThrownBy(() -> sut.customize(factory)).isInstanceOf(IllegalStateException.class);
        for (String address:new String[]{"0.0.0.0","::","192.168.1.12"}) {
            factory.setAddress(InetAddress.getByName(address));
            assertThatThrownBy(() -> sut.customize(factory)).isInstanceOf(IllegalStateException.class);
        }
        factory.setAddress(InetAddress.getByName("127.0.0.1"));sut.customize(factory);
        assertThatThrownBy(() -> new RemoteAccessManagementConfiguration.ManagementBind("native").customize(factory)).isInstanceOf(IllegalStateException.class);
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void combinedRejectsEqualServiceValuesEvenInDifferentFiles() throws Exception {
        String value=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var ra=directory.resolve("remote");var mcp=directory.resolve("general");
        for(var path:java.util.List.of(ra,mcp)) {
            java.nio.file.Files.writeString(path,value);
            java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        var sut=new RemoteAccessManagementConfiguration();
        assertThatThrownBy(() -> sut.remoteAccessServiceFilter(ra,true,mcp)).hasMessage("Service credentials must be distinct");
        assertThat(sut.remoteAccessServiceFilter(ra,false,null).getFilter()).isNotNull();
        java.nio.file.Files.writeString(ra,"r".repeat(43));
        assertThat(sut.remoteAccessServiceFilter(ra,true,mcp).getFilter()).isNotNull();
    }
}
