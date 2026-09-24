package com.sitionix.forgeagent.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpRequestRedactionTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void inboundBearerAndHeaderSecretsAreWriteOnlyAndStringRedacted() throws Exception {
        for (String credential : new String[]{"{\"bearer\":\"bearer-canary\"}",
                "{\"headers\":{\"X-Secret\":\"header-canary\"}}"}) {
            var request=json.readValue("{\"displayName\":\"x\",\"credential\":"+credential+"}",McpConnectionRequest.class);
            assertThat(request.credential()).isNotNull();
            assertThat(request.toString()+request.credential().toString()+json.writeValueAsString(request))
                    .doesNotContain("bearer-canary","header-canary");
        }
    }
}
