package com.sitionix.forgeagent;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.sitionix.forgeagent.infrastructure.local.mcp.registry.McpRegistryFeignClient;

@SpringBootApplication
@EnableFeignClients(clients = McpRegistryFeignClient.class)
public class ForgeAgentApplication {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(final String[] args) {
        SpringApplication.run(ForgeAgentApplication.class, args);
    }
}
