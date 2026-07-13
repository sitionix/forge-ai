package com.sitionix.forgeai.api.proxy;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "forge.ai.infrastructure")
public class InfrastructureProxyProperties {

    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost");

    private ServiceProperties knowledge = new ServiceProperties(URI.create("http://127.0.0.1:7081"));
    private ServiceProperties jarvis = new ServiceProperties(URI.create("http://127.0.0.1:7071"));
    private ProxyProperties proxy = new ProxyProperties();

    @PostConstruct
    public void validate() {
        this.validate("Knowledge", this.knowledge);
        this.validate("Jarvis", this.jarvis);
    }

    public ServiceProperties service(final InfrastructureProxyService service) {
        return switch (service) {
            case KNOWLEDGE -> this.knowledge;
            case JARVIS -> this.jarvis;
        };
    }

    public ServiceProperties getKnowledge() {
        return this.knowledge;
    }

    public void setKnowledge(final ServiceProperties knowledge) {
        this.knowledge = knowledge;
    }

    public ServiceProperties getJarvis() {
        return this.jarvis;
    }

    public void setJarvis(final ServiceProperties jarvis) {
        this.jarvis = jarvis;
    }

    public ProxyProperties getProxy() {
        return this.proxy;
    }

    public void setProxy(final ProxyProperties proxy) {
        this.proxy = proxy;
    }

    private void validate(final String label, final ServiceProperties service) {
        if (service == null || !service.enabled) {
            return;
        }
        if (service.baseUrl == null) {
            throw new IllegalStateException(label + " base URL is required");
        }
        if (!"http".equalsIgnoreCase(service.baseUrl.getScheme())) {
            throw new IllegalStateException(label + " base URL must use http");
        }
        final String host = service.baseUrl.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalStateException(label + " base URL must point to localhost");
        }
    }

    public static class ServiceProperties {
        private boolean enabled = true;
        private URI baseUrl;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(120);

        public ServiceProperties() {
        }

        ServiceProperties(final URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public URI getBaseUrl() {
            return this.baseUrl;
        }

        public void setBaseUrl(final URI baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return this.connectTimeout;
        }

        public void setConnectTimeout(final Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return this.readTimeout;
        }

        public void setReadTimeout(final Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class ProxyProperties {
        private static final Duration DEFAULT_KNOWLEDGE_EXPLANATION_TRANSPORT_GRACE = Duration.ofSeconds(5);
        private static final Duration DEFAULT_JARVIS_QUERY_TRANSPORT_GRACE = Duration.ofSeconds(5);

        private int maxRequestBodyBytes = 1024 * 1024;
        private int maxResponseBodyBytes = 5 * 1024 * 1024;
        private Duration knowledgeExplanationTransportGrace = DEFAULT_KNOWLEDGE_EXPLANATION_TRANSPORT_GRACE;
        private Duration jarvisQueryTransportGrace = DEFAULT_JARVIS_QUERY_TRANSPORT_GRACE;

        public int getMaxRequestBodyBytes() {
            return this.maxRequestBodyBytes;
        }

        public void setMaxRequestBodyBytes(final int maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        public int getMaxResponseBodyBytes() {
            return this.maxResponseBodyBytes;
        }

        public void setMaxResponseBodyBytes(final int maxResponseBodyBytes) {
            this.maxResponseBodyBytes = maxResponseBodyBytes;
        }

        public Duration getKnowledgeExplanationTransportGrace() {
            return this.knowledgeExplanationTransportGrace;
        }

        public void setKnowledgeExplanationTransportGrace(final Duration knowledgeExplanationTransportGrace) {
            this.knowledgeExplanationTransportGrace = knowledgeExplanationTransportGrace;
        }

        public Duration getJarvisQueryTransportGrace() {
            return this.jarvisQueryTransportGrace;
        }

        public void setJarvisQueryTransportGrace(final Duration jarvisQueryTransportGrace) {
            this.jarvisQueryTransportGrace = jarvisQueryTransportGrace;
        }

        Duration knowledgeExplanationReadTimeout(final Duration requestDeadline) {
            if (requestDeadline == null) {
                throw new IllegalArgumentException("Flow explanation request deadline is required");
            }
            final Duration grace = this.knowledgeExplanationTransportGrace == null
                    ? DEFAULT_KNOWLEDGE_EXPLANATION_TRANSPORT_GRACE
                    : this.knowledgeExplanationTransportGrace;
            return requestDeadline.plus(grace);
        }

        Duration jarvisQueryReadTimeout(final Duration requestDeadline) {
            final Duration jarvisKnowledgeTimeout = this.knowledgeExplanationReadTimeout(requestDeadline);
            final Duration grace = this.jarvisQueryTransportGrace == null
                    ? DEFAULT_JARVIS_QUERY_TRANSPORT_GRACE
                    : this.jarvisQueryTransportGrace;
            return jarvisKnowledgeTimeout.plus(grace);
        }

        void validateFlowExplanationTimeoutHierarchy(final Duration requestDeadline) {
            final Duration jarvisKnowledgeTimeout = this.knowledgeExplanationReadTimeout(requestDeadline);
            final Duration nexusJarvisTimeout = this.jarvisQueryReadTimeout(requestDeadline);
            if (jarvisKnowledgeTimeout.compareTo(requestDeadline) <= 0) {
                throw new IllegalStateException(
                        "Jarvis Knowledge flow explanation transport timeout must exceed the Knowledge deadline"
                );
            }
            if (nexusJarvisTimeout.compareTo(jarvisKnowledgeTimeout) <= 0) {
                throw new IllegalStateException(
                        "Nexus Jarvis query transport timeout must exceed the Jarvis Knowledge transport timeout"
                );
            }
        }
    }
}
