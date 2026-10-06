package com.sitionix.forgeremote.nexus;

import com.sitionix.forgeai.RemoteAccessOperatorConfiguration;
import com.sitionix.forgeai.infrastructure.agentclient.ForgeAgentClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Composition root for the existing dedicated Remote Access management process. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration.class})
@EnableConfigurationProperties(ForgeAgentClientProperties.class)
@ComponentScan(basePackages = {
        "com.sitionix.forgeai.api.remoteaccess",
        "com.sitionix.forgeai.application.remoteaccess",
        "com.sitionix.forgeai.infrastructure.agentclient.remoteaccess"})
@Import(RemoteAccessOperatorConfiguration.class)
public class RemoteAccessNexusApplication {
    public static void main(String[] args) {
        SpringApplication.run(RemoteAccessNexusApplication.class, args);
    }
}
