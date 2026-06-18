package com.sitionix.forgeai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OperatorUiStartupLogger {

    private static final String OPERATOR_UI_PATH = "/operator/index.html";

    private final ObjectProvider<ServletWebServerApplicationContext> webServerApplicationContextProvider;
    private final Environment environment;

    public OperatorUiStartupLogger(
            final ObjectProvider<ServletWebServerApplicationContext> webServerApplicationContextProvider,
            final Environment environment
    ) {
        this.webServerApplicationContextProvider = webServerApplicationContextProvider;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOperatorUiUrl() {
        final ServletWebServerApplicationContext context = this.webServerApplicationContextProvider == null
                ? null
                : this.webServerApplicationContextProvider.getIfAvailable();
        if (context == null || context.getWebServer() == null) {
            return;
        }
        final int port = context.getWebServer().getPort();
        final String contextPath = this.environment.getProperty("server.servlet.context-path", "");
        log.info("Forge AI operator UI available at {}", this.operatorUiUrl(port, contextPath));
    }

    String operatorUiUrl(final int port, final String contextPath) {
        return "http://localhost:%d%s%s".formatted(port, this.normalizeContextPath(contextPath), OPERATOR_UI_PATH);
    }

    private String normalizeContextPath(final String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "";
        }
        String normalized = contextPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
