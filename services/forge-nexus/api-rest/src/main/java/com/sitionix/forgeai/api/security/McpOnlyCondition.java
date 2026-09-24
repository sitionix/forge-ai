package com.sitionix.forgeai.api.security;

public final class McpOnlyCondition implements org.springframework.context.annotation.Condition {
    @Override public boolean matches(org.springframework.context.annotation.ConditionContext context,
            org.springframework.core.type.AnnotatedTypeMetadata metadata) {
        return !context.getEnvironment().getProperty("forge.remote-access.enabled",Boolean.class,false);
    }
}
