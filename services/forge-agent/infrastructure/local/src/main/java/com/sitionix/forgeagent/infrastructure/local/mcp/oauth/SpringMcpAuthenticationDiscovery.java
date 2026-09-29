package com.sitionix.forgeagent.infrastructure.local.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sitionix.forgeagent.domain.exception.McpOAuthException;
import com.sitionix.forgeagent.domain.model.McpAuthenticationMetadata;
import com.sitionix.forgeagent.domain.model.McpOAuthConfiguration;
import com.sitionix.forgeagent.domain.port.McpAuthenticationDiscovery;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;

final class SpringMcpAuthenticationDiscovery implements McpAuthenticationDiscovery {
    private static final String TOKEN="[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final Pattern CHALLENGE_PART=Pattern.compile("\\G\\s*(?:,\\s*)?("+TOKEN+")(?:\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|"+TOKEN+"))?");
    private final McpOAuthMetadataHttpClient http;
    private final Set<URI> configuredIssuers;
    SpringMcpAuthenticationDiscovery(McpOAuthMetadataHttpClient http,Set<URI> configuredIssuers){this.http=http;this.configuredIssuers=Set.copyOf(configuredIssuers);}
    @Override public McpAuthenticationMetadata discover(URI endpoint,long deadline) {
        try {
            var response=http.challenge(endpoint,deadline);
            if(response.status()>=200 && response.status()<300)return McpAuthenticationMetadata.noAuth(endpoint);
            if(response.status()!=401)throw McpOAuthException.unavailable();
            var challenge=parseChallenge(response.headers());
            ResourceMetadata resource;
            if(challenge.containsKey("resource_metadata"))resource=http.metadata(uri(challenge.get("resource_metadata")),deadline,ResourceMetadata.class);
            else resource=first(resourceUris(endpoint),deadline,ResourceMetadata.class);
            if(resource==null && !challenge.containsKey("resource_metadata"))throw McpOAuthException.customRequired();
            if(resource==null || !endpoint.equals(resource.resource()) || resource.servers()==null || resource.servers().isEmpty())
                throw McpOAuthException.invalidResponse();
            resource.servers().forEach(SpringMcpAuthenticationDiscovery::validateUri);
            var candidates=resource.servers().size()==1?resource.servers():resource.servers().stream().filter(configuredIssuers::contains).toList();
            if(candidates.size()!=1)throw McpOAuthException.invalidResponse();
            URI issuer=candidates.get(0);
            var server=first(serverUris(issuer),deadline,ServerMetadata.class);
            if(server==null || !issuer.equals(server.issuer()) || server.authorization()==null || server.token()==null
                    || server.codeChallengeMethods()==null || !server.codeChallengeMethods().contains("S256")
                    || server.responseTypes()==null || !server.responseTypes().contains("code")
                    || server.grants()!=null && !server.grants().contains("authorization_code"))throw McpOAuthException.invalidResponse();
            validateValues(server.authMethods());validateValues(server.codeChallengeMethods());
            for(URI target:new URI[]{server.authorization(),server.token(),server.revocation(),server.registration()})if(target!=null)validateUri(target);
            Set<String> scopes=challenge.containsKey("scope")?scopes(challenge.get("scope")):Set.of();
            return new McpAuthenticationMetadata(true,resource.resource(),issuer,server.authorization(),server.token(),server.revocation(),server.registration(),
                    Boolean.TRUE.equals(server.cimd()),scopes,server.authMethods()==null?Set.of("client_secret_basic"):server.authMethods(),server.codeChallengeMethods());
        } catch(RestClientException transport){throw McpOAuthException.unavailable();}
    }
    private <T>T first(List<URI> uris,long deadline,Class<T> type) {
        for(URI uri:uris){T value=http.metadata(uri,deadline,type);if(value!=null)return value;}return null;
    }
    private static List<URI> resourceUris(URI endpoint) {
        String origin=endpoint.getScheme()+"://"+endpoint.getRawAuthority();String path=endpoint.getRawPath();
        return path==null || path.isEmpty() || path.equals("/")?List.of(URI.create(origin+"/.well-known/oauth-protected-resource")):
                List.of(URI.create(origin+"/.well-known/oauth-protected-resource"+path),URI.create(origin+"/.well-known/oauth-protected-resource"));
    }
    private static List<URI> serverUris(URI issuer) {
        String origin=issuer.getScheme()+"://"+issuer.getRawAuthority();String path=issuer.getRawPath();
        if(path==null || path.isEmpty() || path.equals("/"))return List.of(URI.create(origin+"/.well-known/oauth-authorization-server"),URI.create(origin+"/.well-known/openid-configuration"));
        return List.of(URI.create(origin+"/.well-known/oauth-authorization-server"+path),URI.create(origin+"/.well-known/openid-configuration"+path),
                URI.create(issuer.toString().replaceAll("/$","")+"/.well-known/openid-configuration"));
    }
    private static Map<String,String> parseChallenge(HttpHeaders headers) {
        Map<String,String> result=new HashMap<>();boolean seen=false;
        for(String header:headers.getOrEmpty(HttpHeaders.WWW_AUTHENTICATE)) {
            var matcher=CHALLENGE_PART.matcher(header);int position=0;boolean bearer=false;
            while(position<header.length()) {
                if(!matcher.find() || matcher.start()!=position)throw McpOAuthException.invalidResponse();position=matcher.end();
                String name=matcher.group(1),value=matcher.group(2);
                if(value==null){bearer=name.equalsIgnoreCase("Bearer");if(bearer){if(seen)throw McpOAuthException.invalidResponse();seen=true;}continue;}
                if(bearer) {
                    if(value.startsWith("\""))value=value.substring(1,value.length()-1).replaceAll("\\\\(.)","$1");
                    if(result.putIfAbsent(name.toLowerCase(Locale.ROOT),value)!=null)throw McpOAuthException.invalidResponse();
                }
            }
        }
        return result;
    }
    private static Set<String> scopes(String value) {
        if(value.isBlank())return Set.of();var result=new HashSet<String>();
        for(String scope:value.split(" ")){if(!scope.matches("[\\x21\\x23-\\x5B\\x5D-\\x7E]+"))throw McpOAuthException.invalidResponse();result.add(scope);}return Set.copyOf(result);
    }
    private static void validateValues(Set<String> values){if(values!=null && values.stream().anyMatch(v->v==null || v.isBlank()))throw McpOAuthException.invalidResponse();}
    private static URI uri(String value){try{return URI.create(value);}catch(IllegalArgumentException invalid){throw McpOAuthException.invalidResponse();}}
    private static void validateUri(URI value){try{McpOAuthConfiguration.validateUri(value);}catch(IllegalArgumentException invalid){throw McpOAuthException.invalidResponse();}}
    private record ResourceMetadata(URI resource,@JsonProperty("authorization_servers") List<URI> servers){}
    private record ServerMetadata(URI issuer,@JsonProperty("authorization_endpoint") URI authorization,
            @JsonProperty("token_endpoint") URI token,@JsonProperty("revocation_endpoint") URI revocation,
            @JsonProperty("registration_endpoint") URI registration,@JsonProperty("client_id_metadata_document_supported") Boolean cimd,
            @JsonProperty("token_endpoint_auth_methods_supported") Set<String> authMethods,
            @JsonProperty("code_challenge_methods_supported") Set<String> codeChallengeMethods,
            @JsonProperty("response_types_supported") Set<String> responseTypes,@JsonProperty("grant_types_supported") Set<String> grants){}
}
