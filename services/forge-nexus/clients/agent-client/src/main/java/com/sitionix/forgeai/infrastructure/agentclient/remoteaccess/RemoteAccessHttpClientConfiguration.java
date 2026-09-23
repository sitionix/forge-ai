package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
@Configuration
@ConditionalOnProperty(name="forge.remote-access.enabled",havingValue="true")
public class RemoteAccessHttpClientConfiguration {
    @Bean RemoteAccessHttpClient remoteAccessHttpClient(ForgeAgentClientProperties properties,
            @Value("${forge.remote-access.service-secret-file}") Path secretFile) throws UnknownHostException {
        URI uri=properties.getBaseUrl();
        if (!properties.enabled() || uri==null || !"http".equals(uri.getScheme()) || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null
                || !java.util.Arrays.stream(InetAddress.getAllByName(uri.getHost())).allMatch(InetAddress::isLoopbackAddress)) {
            throw new IllegalStateException("Remote Access requires an enabled loopback Agent URL");
        }
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(properties.getReadTimeout());
        var client=RestClient.builder().baseUrl(uri.toString()).requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+RemoteAccessSecretFile.read(secretFile)).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(RemoteAccessHttpClient.class);
    }
}
