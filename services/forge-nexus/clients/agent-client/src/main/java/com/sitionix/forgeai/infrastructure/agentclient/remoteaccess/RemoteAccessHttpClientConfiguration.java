package com.sitionix.forgeai.infrastructure.agentclient.remoteaccess;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;
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
    // Stage 5 SSH revoke is bounded at 90s; allow 10s for HTTP/process overhead.
    private static final Duration MINIMUM_LIFECYCLE_READ_TIMEOUT = Duration.ofSeconds(100);
    @Bean RemoteAccessHttpClient remoteAccessHttpClient(ForgeAgentClientProperties properties,
            @Value("${forge.remote-access.service-secret-file}") Path secretFile,
            @Value("${forge.remote-access.agent-read-timeout:120s}") String configuredReadTimeout) throws UnknownHostException {
        Duration readTimeout = DurationStyle.detectAndParse(configuredReadTimeout);
        if (readTimeout.compareTo(MINIMUM_LIFECYCLE_READ_TIMEOUT) < 0) {
            throw new IllegalStateException("forge.remote-access.agent-read-timeout must be at least "
                    + MINIMUM_LIFECYCLE_READ_TIMEOUT.toSeconds() + "s for bounded lifecycle operations");
        }
        URI uri=properties.getBaseUrl();
        if (!properties.enabled() || uri==null || !"http".equals(uri.getScheme()) || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null
                || !java.util.Arrays.stream(InetAddress.getAllByName(uri.getHost())).allMatch(InetAddress::isLoopbackAddress)) {
            throw new IllegalStateException("Remote Access requires an enabled loopback Agent URL");
        }
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(readTimeout);
        var client=RestClient.builder().baseUrl(uri.toString()).requestFactory(factory)
                .defaultHeader("Authorization","Bearer "+RemoteAccessSecretFile.read(secretFile)).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(RemoteAccessHttpClient.class);
    }
}
