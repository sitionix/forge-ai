package com.sitionix.forgeagent.infrastructure.local.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

class McpRegistryRuntimeConfigurationTest {
    @Test
    void normalRuntimeCanWaitForARegistryPageBeyondTheFormerFiveSecondBudget() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v0.1/servers", exchange -> {
            received.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body = "{\"servers\":[],\"metadata\":{\"nextCursor\":\"next\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var caller = Executors.newSingleThreadExecutor()) {
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
                        try {
                            for (var source : new YamlPropertySourceLoader().load("normal-runtime", new ClassPathResource("application.yml"))) {
                                context.getEnvironment().getPropertySources().addLast(source);
                            }
                        } catch (java.io.IOException error) {
                            throw new IllegalStateException(error);
                        }
                    })
                    .withUserConfiguration(McpRegistryHttpClientConfiguration.class)
                    .withBean(RestClient.Builder.class, RestClient::builder)
                    .withPropertyValues("forge.mcp.registry.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var response = caller.submit(() -> context.getBean(McpRegistryHttpClient.class).list(null, null, 20, "latest"));
                        try {
                            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
                            // Hold the response across the old deadline, without guessing request start order.
                            assertThatThrownBy(() -> response.get(6, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
                            release.countDown();
                            var page = response.get(3, TimeUnit.SECONDS);
                            assertThat(page.servers()).isEmpty();
                            assertThat(page.metadata().nextCursor()).isEqualTo("next");
                        } finally {
                            release.countDown();
                        }
                    });
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
