package com.sitionix.forgeai.infrastructure.agentclient.standalone;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Arrays;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import com.sitionix.forgeai.infrastructure.agentclient.library.ServiceTokenFile;
import com.sitionix.forgeai.infrastructure.agentclient.remoteaccess.RemoteAccessSecretFile;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
@Configuration(proxyBeanMethods=false)
public class StandaloneSessionHttpClientConfiguration {
 @Bean StandaloneAgentSessionHttpClient standaloneAgentSessionHttpClient(ForgeAgentClientProperties properties,Environment environment){
  var http=HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
  var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(properties.getReadTimeout());
  var rest=RestClient.builder().baseUrl(properties.getBaseUrl().toString()).requestFactory(factory).requestInterceptor((r,body,next)->{
   URI base=properties.getBaseUrl();try{if(!"http".equals(base.getScheme())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!Arrays.stream(InetAddress.getAllByName(base.getHost())).allMatch(InetAddress::isLoopbackAddress))throw new IllegalArgumentException();}
   catch(Exception invalid){throw new ResourceAccessException("Session service must use trusted loopback transport");}
   String file=environment.resolveRequiredPlaceholders("${forge.ai-library.service-token-file:${forge.remote-access.service-secret-file:${FORGE_CODEX_SERVICE_TOKEN_FILE:/etc/forge-ai/codex-service/token}}}");
   try{String secret=!environment.containsProperty("forge.ai-library.service-token-file")&&environment.containsProperty("forge.remote-access.service-secret-file")?RemoteAccessSecretFile.read(Path.of(file)):ServiceTokenFile.read(Path.of(file));r.getHeaders().setBearerAuth(secret);}
   catch(RuntimeException unavailable){throw new ResourceAccessException("Session service identity is unavailable");}
   return next.execute(r,body);
  }).build();
  return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(StandaloneAgentSessionHttpClient.class);
 }
}
