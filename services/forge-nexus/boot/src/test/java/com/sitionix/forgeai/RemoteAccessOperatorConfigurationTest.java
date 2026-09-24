package com.sitionix.forgeai;
import static org.assertj.core.api.Assertions.*;
import com.sitionix.forgeai.api.remoteaccess.RemoteAccessOperatorAuthentication;
import java.net.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
class RemoteAccessOperatorConfigurationTest {
    @Test void explicitLoopbackAndContextScopedCookieAreRequired() throws Exception {
        var auth=new RemoteAccessOperatorAuthentication("a".repeat(43).getBytes(),URI.create("http://127.0.0.1:9099"),Clock.systemUTC());
        var sut=new RemoteAccessOperatorConfiguration.OperatorBind(auth,"/fgaisox","none");
        var factory=new TomcatServletWebServerFactory();
        assertThatThrownBy(() -> sut.customize(factory)).isInstanceOf(IllegalStateException.class);
        factory.setAddress(InetAddress.getByName("0.0.0.0"));
        assertThatThrownBy(() -> sut.customize(factory)).isInstanceOf(IllegalStateException.class);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));sut.customize(factory);
        assertThat(factory.getSession().getCookie().getPath()).isEqualTo("/fgaisox/api/v1/infrastructure/agents/remote-access");
        assertThat(factory.getSession().getCookie().getHttpOnly()).isTrue();
        assertThat(factory.getSession().getCookie().getSameSite().name()).isEqualTo("STRICT");
        assertThatThrownBy(() -> new RemoteAccessOperatorConfiguration.OperatorBind(auth,"/fgaisox","framework").customize(factory)).isInstanceOf(IllegalStateException.class);
    }
}
