package com.sitionix.forgeai.infrastructure.agentclient.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Same lossless numeric decoding as Agent's MCP protocol; scoped to opaque schemas. */
public final class McpSchemaJsonDeserializer extends JsonDeserializer<Map<String,Object>> {
    @Override public Map<String,Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return ((ObjectMapper)parser.getCodec()).readerFor(new TypeReference<Map<String,Object>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(parser);
    }
}
