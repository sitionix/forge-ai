package com.sitionix.forgeai.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Locale;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record JarvisKnowledgeQueryRequest(
        @NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String queryText,
        JarvisKnowledgeQueryIntent intent,
        @JsonDeserialize(using = StrictStringDeserializer.class) String answerLanguage,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean includeTests,
        @Min(1) @Max(10) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer maxFlows
) {
    public JarvisKnowledgeQueryRequest normalized() {
        final String normalizedQueryText = this.queryText == null ? null : this.queryText.trim();
        final JarvisKnowledgeQueryIntent normalizedIntent = this.intent == null ? JarvisKnowledgeQueryIntent.UNKNOWN : this.intent;
        final String normalizedLanguage = this.answerLanguage == null || this.answerLanguage.isBlank()
                ? "en"
                : this.answerLanguage.trim().toLowerCase(Locale.ROOT);
        final Boolean normalizedIncludeTests = this.includeTests == null ? Boolean.FALSE : this.includeTests;
        final Integer normalizedMaxFlows = this.maxFlows == null ? 10 : this.maxFlows;
        return new JarvisKnowledgeQueryRequest(
                normalizedQueryText,
                normalizedIntent,
                normalizedLanguage,
                normalizedIncludeTests,
                normalizedMaxFlows
        );
    }
}

final class StrictStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw JsonMappingException.from(parser, "value must be a string");
        }
        return parser.getValueAsString();
    }
}

final class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE) {
            return parser.getBooleanValue();
        }
        throw JsonMappingException.from(parser, "includeTests must be a boolean");
    }
}

final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getIntValue();
        }
        throw JsonMappingException.from(parser, "maxFlows must be an integer");
    }
}
