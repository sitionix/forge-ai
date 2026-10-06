package com.sitionix.forgeai;

import com.sitionix.forgeai.api.llm.LlmAuthorizationBrowserProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(LlmAuthorizationBrowserProperties.class)
class LlmAuthorizationBrowserConfiguration {}
