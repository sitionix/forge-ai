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
}
