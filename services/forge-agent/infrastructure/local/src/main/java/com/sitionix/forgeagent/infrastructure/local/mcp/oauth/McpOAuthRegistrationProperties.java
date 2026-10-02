package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;
import com.sitionix.forgeagent.domain.model.McpOAuthConfiguration;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("forge.mcp.oauth")
public record McpOAuthRegistrationProperties(@DefaultValue List<Client> clients,URI clientIdMetadataUri) {
    public McpOAuthRegistrationProperties {
        clients=List.copyOf(clients);
        if(clients.stream().map(Client::issuer).distinct().count()!=clients.size())throw new IllegalArgumentException("Duplicate OAuth client issuer");
        if(clientIdMetadataUri!=null){McpOAuthConfiguration.validateUri(clientIdMetadataUri);if(!"https".equals(clientIdMetadataUri.getScheme()) || clientIdMetadataUri.getPath().isEmpty())throw new IllegalArgumentException("OAuth client metadata must have an HTTPS URL with a path");}
    }
    public record Client(URI issuer,String clientId,@DefaultValue("none") String clientAuthenticationMethod,Path clientSecretFile,@DefaultValue Set<String> scopes) {
        public Client {
            McpOAuthConfiguration.validateUri(issuer);
            if(clientId==null || clientId.isBlank() || clientId.contains("\r") || clientId.contains("\n")
                    || !Set.of("none","client_secret_basic","client_secret_post").contains(clientAuthenticationMethod)
                    || (!"none".equals(clientAuthenticationMethod))!=(clientSecretFile!=null))throw new IllegalArgumentException("Invalid registered OAuth client");
            scopes=Set.copyOf(scopes);
            if(scopes.stream().anyMatch(s->!s.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+")))throw new IllegalArgumentException("Invalid registered OAuth scopes");
        }
    }
}
