package com.sitionix.forgeagent.application.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ForgeAgentApplicationConfiguration {

    @Bean
    Clock forgeAgentClock() {
        return Clock.systemUTC();
    }
}
