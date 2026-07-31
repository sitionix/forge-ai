package com.sitionix.forgeai.infrastructure.knowledgeclient.activeprofile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Request;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@Configuration
@EnableConfigurationProperties(KnowledgeActiveProfileClientProperties.class)
public class KnowledgeActiveProfileFeignConfiguration {

    @Bean
    ErrorDecoder knowledgeActiveProfileErrorDecoder() {
        return new KnowledgeActiveProfileErrorDecoder(this.strictObjectMapper());
    }

    @Bean
    RequestInterceptor knowledgeActiveProfileCorrelationInterceptor() {
        return template -> template.header(KnowledgeActiveProfileCorrelation.HEADER, KnowledgeActiveProfileCorrelation.currentOrNew());
    }

    @Bean
    Request.Options knowledgeActiveProfileRequestOptions(final KnowledgeActiveProfileClientProperties properties) {
        return new Request.Options(
                Math.toIntExact(properties.getConnectTimeout().toMillis()),
                TimeUnit.MILLISECONDS,
                Math.toIntExact(properties.getReadTimeout().toMillis()),
                TimeUnit.MILLISECONDS,
                true
        );
    }

    @Bean
    Decoder knowledgeActiveProfileDecoder() {
        return new ResponseEntityDecoder(new SpringDecoder(this.httpMessageConverters()));
    }

    @Bean
    Encoder knowledgeActiveProfileEncoder() {
        return new SpringEncoder(this.httpMessageConverters());
    }

    private ObjectFactory<HttpMessageConverters> httpMessageConverters() {
        return () -> new HttpMessageConverters(new MappingJackson2HttpMessageConverter(this.strictObjectMapper()));
    }

    private ObjectMapper strictObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }
}
