package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.McpOAuthClientRegistrationProvider;
import com.sitionix.forgeagent.infrastructure.local.mcp.ProtectedMcpKeySource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.client.RestClientException;

final class SpringMcpOAuthClientRegistrationProvider implements McpOAuthClientRegistrationProvider {
    private final McpOAuthMetadataHttpClient http;
    private final McpOAuthRegistrationProperties properties;
    private final URI callback;
    SpringMcpOAuthClientRegistrationProvider(McpOAuthMetadataHttpClient http,McpOAuthRegistrationProperties properties,URI callback){this.http=http;this.properties=properties;this.callback=callback;}
    @Override public McpOAuthClientRegistration resolve(McpAuthenticationMetadata metadata,long deadline) {
        if(System.nanoTime()>=deadline)throw McpOAuthException.unavailable();
        for(var client:properties.clients())if(client.issuer().equals(metadata.issuer()))return installed(metadata,client);
        try {
            if(metadata.clientIdMetadataSupported() && properties.clientIdMetadataUri()!=null)return clientMetadata(metadata,deadline);
            if(metadata.registrationEndpoint()!=null)return dynamic(metadata,deadline);
            throw McpOAuthException.setupRequired();
        } catch(RestClientException unavailable){throw McpOAuthException.unavailable();}
    }
    private McpOAuthClientRegistration installed(McpAuthenticationMetadata m,McpOAuthRegistrationProperties.Client client) {
        if(!m.clientAuthenticationMethods().isEmpty() && !m.clientAuthenticationMethods().contains(client.clientAuthenticationMethod()))throw McpOAuthException.setupRequired();
        if(!client.scopes().isEmpty() && !client.scopes().containsAll(m.scopes()))throw McpOAuthException.setupRequired();
        Set<String> scopes=m.scopes().isEmpty()?client.scopes():m.scopes();
        String secret=null;
        if(client.clientSecretFile()!=null){
            byte[] bytes=ProtectedMcpKeySource.readProtected(client.clientSecretFile());
            try {secret=new String(bytes,StandardCharsets.UTF_8).stripTrailing();}finally{Arrays.fill(bytes,(byte)0);}
            if(secret.isBlank() || secret.contains("\r") || secret.contains("\n"))throw McpOAuthException.setupRequired();
        }
        return result(m,client.clientId(),client.clientAuthenticationMethod(),scopes,secret);
    }
    private McpOAuthClientRegistration clientMetadata(McpAuthenticationMetadata m,long deadline) {
        URI uri=properties.clientIdMetadataUri();var document=http.metadata(uri,deadline,ClientDocument.class);
        if(document==null || !uri.toString().equals(document.clientId()) || document.clientName()==null || document.clientName().isBlank()
                || document.redirectUris()==null || !document.redirectUris().contains(callback) || document.method()!=null && !"none".equals(document.method())
                || !m.clientAuthenticationMethods().contains("none"))throw McpOAuthException.invalidResponse();
        return result(m,uri.toString(),"none",m.scopes(),null);
    }
    private McpOAuthClientRegistration dynamic(McpAuthenticationMetadata m,long deadline) {
        String method=m.clientAuthenticationMethods().isEmpty()?"client_secret_basic":List.of("none","client_secret_basic","client_secret_post").stream().filter(m.clientAuthenticationMethods()::contains).findFirst().orElseThrow(McpOAuthException::setupRequired);
        String application=Set.of("127.0.0.1","localhost","::1","[::1]").contains(callback.getHost())?"native":"web";
        var request=new RegistrationRequest("Forge",List.of(callback),List.of("authorization_code"),List.of("code"),method,application,m.scopes().isEmpty()?null:String.join(" ",new TreeSet<>(m.scopes())));
        var response=http.register(m.registrationEndpoint(),deadline,request,RegistrationResponse.class);
        if(response.clientId()==null || response.clientId().isBlank() || response.clientId().contains("\r") || response.clientId().contains("\n")
                || !method.equals(response.method()==null?"client_secret_basic":response.method())
                || response.secretExpiresAt()!=null && response.secretExpiresAt()!=0 && response.secretExpiresAt()<=java.time.Instant.now().getEpochSecond()
                || response.redirectUris()!=null && !response.redirectUris().equals(List.of(callback))
                || response.clientSecret()!=null && (response.clientSecret().isBlank() || response.clientSecret().contains("\r") || response.clientSecret().contains("\n"))
                || !"none".equals(method) && response.clientSecret()==null)
            throw McpOAuthException.invalidResponse();
        return result(m,response.clientId(),method,m.scopes(),response.clientSecret());
    }
    private static McpOAuthClientRegistration result(McpAuthenticationMetadata m,String id,String method,Set<String> scopes,String secret) {
        return new McpOAuthClientRegistration(new McpOAuthConfiguration(m.issuer(),m.authorizationEndpoint(),m.tokenEndpoint(),m.revocationEndpoint(),id,method,scopes,m.resource()),new McpOAuthCredentials(secret,null));
    }
    private record ClientDocument(@JsonProperty("client_id") String clientId,@JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<URI> redirectUris,@JsonProperty("token_endpoint_auth_method") String method){}
    private record RegistrationRequest(@JsonProperty("client_name") String clientName,@JsonProperty("redirect_uris") List<URI> redirectUris,
            @JsonProperty("grant_types") List<String> grants,@JsonProperty("response_types") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") String method,@JsonProperty("application_type") String applicationType,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String scope){}
    private record RegistrationResponse(@JsonProperty("client_id") String clientId,@JsonProperty("client_secret") String clientSecret,
            @JsonProperty("token_endpoint_auth_method") String method,@JsonProperty("redirect_uris") List<URI> redirectUris,
            @JsonProperty("client_secret_expires_at") Long secretExpiresAt) {
        @Override public String toString(){return "RegistrationResponse[redacted]";}
    }
}
