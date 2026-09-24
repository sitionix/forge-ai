package com.sitionix.forgeai;

import com.sitionix.forgeai.api.security.ProtectedNexusFile;
import com.sitionix.forgeai.infrastructure.agentclient.remoteaccess.RemoteAccessSecretFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Combined mode has one RA operator authority and two distinct service audiences. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"forge.mcp.enabled","forge.remote-access.enabled"},havingValue="true")
public class CombinedOperatorCredentialConfiguration {
    @Bean Object combinedOperatorCredentials(
            @Value("${forge.remote-access.operator-secret-file}") Path operator,
            @Value("${forge.remote-access.service-secret-file}") Path remoteService,
            @Value("${forge.remote-access.operator-origin}") URI origin,
            @Value("${forge.mcp.agent-service-credential-file}") Path mcpService,
            @Value("${forge.mcp.bootstrap-credential-file:#{null}}") Path alias,
            @Value("${forge.mcp.operator-origin:#{null}}") URI originAlias) {
        if ((alias!=null && !alias.toAbsolutePath().normalize().equals(operator.toAbsolutePath().normalize()))
                || (originAlias!=null && !equivalent(origin,originAlias)))
            throw new IllegalStateException("Conflicting operator configuration");
        byte[] bootstrap=RemoteAccessSecretFile.read(operator).getBytes(StandardCharsets.UTF_8);
        byte[] remote=RemoteAccessSecretFile.read(remoteService).getBytes(StandardCharsets.UTF_8);
        byte[] general=ProtectedNexusFile.read(mcpService);
        try {
            byte[] normalized=new String(general,StandardCharsets.US_ASCII).strip().getBytes(StandardCharsets.US_ASCII);
            try {
                if (MessageDigest.isEqual(bootstrap,remote) || MessageDigest.isEqual(bootstrap,normalized)
                        || MessageDigest.isEqual(remote,normalized))
                    throw new IllegalStateException("Protected credentials must be distinct");
            } finally { Arrays.fill(normalized,(byte)0); }
        } finally { Arrays.fill(bootstrap,(byte)0);Arrays.fill(remote,(byte)0);Arrays.fill(general,(byte)0); }
        return new Object();
    }
    private static boolean equivalent(URI expected,URI candidate) {
        return candidate.getHost()!=null && candidate.getRawUserInfo()==null && candidate.getRawQuery()==null
                && candidate.getRawFragment()==null && (candidate.getRawPath().isEmpty() || candidate.getRawPath().equals("/"))
                && expected.getScheme().equalsIgnoreCase(candidate.getScheme())
                && expected.getHost().equalsIgnoreCase(candidate.getHost()) && port(expected)==port(candidate);
    }
    private static int port(URI value) { return value.getPort()!=-1?value.getPort():("https".equalsIgnoreCase(value.getScheme())?443:80); }
}
