package probe;
import java.util.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
@SpringBootApplication
public class Probe {
 @Bean HttpServletStreamableServerTransportProvider transport() {return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();}
 @Bean ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servlet(HttpServletStreamableServerTransportProvider t) {var b=new ServletRegistrationBean<>(t,"/mcp");b.setAsyncSupported(true);return b;}
 @Bean io.modelcontextprotocol.server.McpSyncServer server(HttpServletStreamableServerTransportProvider t) {
  var tool=McpSchema.Tool.builder().name("echo").description("synthetic echo").inputSchema(new McpSchema.JsonSchema("object",Map.of("text",Map.of("type","string")),List.of("text"),false,null,null)).build();
  return McpServer.sync(t).serverInfo("fixture","0").capabilities(McpSchema.ServerCapabilities.builder().tools(true).build()).tools(new McpServerFeatures.SyncToolSpecification(tool,(exchange,args)->new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("fixture:"+args.get("text"))),false))).build();
 }
 public static void main(String[] args) {
  try(var ctx=(ServletWebServerApplicationContext)SpringApplication.run(Probe.class,"--server.address=127.0.0.1","--server.port=0","--spring.main.banner-mode=off")) {
   for (var version : List.of("2025-03-26", "2025-06-18", "2025-11-25")) {
   var transport=HttpClientStreamableHttpTransport.builder("http://127.0.0.1:"+ctx.getWebServer().getPort()).endpoint("/mcp").supportedProtocolVersions(List.of(version)).build();
   try(var client=McpClient.sync(transport).build()) {
    var init=client.initialize(); var list=client.listTools(); var result=client.callTool(new McpSchema.CallToolRequest("echo",Map.of("text","sdk")));
    if(list.tools().size()!=1 || !result.content().toString().contains("fixture:sdk") || Boolean.TRUE.equals(result.isError()))throw new AssertionError(result);
    System.out.println("SDK_PROBE_PASS protocol="+init.protocolVersion()+" tools="+list.tools().size()+" result="+result);
    }
    var request=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://fixture.invalid/authorize").clientId("synthetic-client").redirectUri("https://forge.invalid/callback").state("synthetic-state");
    org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers.withPkce().accept(request);
    if(!"S256".equals(request.build().getAdditionalParameters().get("code_challenge_method")))throw new AssertionError("PKCE S256 missing");
    System.out.println("OAUTH_PKCE_S256_PASS library="+org.springframework.security.oauth2.client.registration.ClientRegistration.class.getPackage().getImplementationVersion());
   }
  }
 }
}
