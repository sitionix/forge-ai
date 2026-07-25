package com.sitionix.forgeai.api.proxy;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureProxyConfiguration {

    @Bean
    HttpClient infrastructureProxyHttpClient(final InfrastructureProxyProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.connectTimeout(properties))
                .build();
    }

    private Duration connectTimeout(final InfrastructureProxyProperties properties) {
        final Duration knowledge = properties.getKnowledge().getConnectTimeout();
        final Duration jarvis = properties.getJarvis().getConnectTimeout();
        if (knowledge.compareTo(jarvis) <= 0) {
            return knowledge;
        }
        return jarvis;
    }
}
