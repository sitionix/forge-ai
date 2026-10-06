package com.sitionix.forgeai.api.llm;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("forge.llm.authorization")
public record LlmAuthorizationBrowserProperties(@DefaultValue("http://127.0.0.1:9099") URI browserOrigin,
                                                @DefaultValue("12h") Duration bindingTtl) {
    public LlmAuthorizationBrowserProperties {
        if(browserOrigin==null || browserOrigin.getHost()==null || browserOrigin.getRawUserInfo()!=null
                || browserOrigin.getRawQuery()!=null || browserOrigin.getRawFragment()!=null || !browserOrigin.getPath().isEmpty()
                || !("http".equals(browserOrigin.getScheme()) || "https".equals(browserOrigin.getScheme()))
                || browserOrigin.getPort()>65535 || bindingTtl==null || bindingTtl.isNegative() || bindingTtl.compareTo(Duration.ofSeconds(1))<0)
            throw new IllegalArgumentException("Invalid LLM browser configuration");
    }
}
