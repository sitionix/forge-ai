package com.sitionix.forgeai.api.agentproxy;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkingRepositoriesJacksonConfiguration {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictWorkingRepositoryBooleans() {
        // Workspace builders use boxed Boolean; existing primitive boolean contracts retain their policy.
        return builder -> builder.postConfigurer(mapper -> mapper.coercionConfigFor(Boolean.class)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail));
    }
}
