package com.sitionix.forgeai.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpRequestRedactionTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void inboundBearerAndHeaderSecretsAreWriteOnlyAndCommandStringRedacted() throws Exception {
        for (String credential : new String[]{"{\"bearer\":\"bearer-canary\"}",
                "{\"headers\":{\"X-Secret\":\"header-canary\"}}"}) {
            var request=json.readValue("{\"displayName\":\"x\",\"credentialChange\":\"REPLACE\",\"credential\":"+credential+"}",McpConnectionRequest.class);
            var command=request.toCommand();
            assertThat(request.toString()+request.credential().toString()+command.toString()+json.writeValueAsString(request))
                    .doesNotContain("bearer-canary","header-canary");
        }
    }
}
