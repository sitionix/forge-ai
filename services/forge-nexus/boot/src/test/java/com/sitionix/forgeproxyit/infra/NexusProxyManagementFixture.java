package com.sitionix.forgeproxyit.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Normal operator login through existing ForgeIT endpoint contracts. */
public abstract class NexusProxyManagementFixture extends NexusManagementFixture {
    @Autowired private NexusProxyTestManager manager;
    protected String operatorSession;
    protected String operatorCsrf;
    @BeforeEach
    void loginOperator() {
        manager.mockMvc().ping(NexusOperatorMockMvcEndpoints.login())
                .header("Host", "127.0.0.1:9099").header("Origin", "http://127.0.0.1:9099")
                .andExpectPath(result -> {
                    String cookie = result.getResponse().getHeader("Set-Cookie");
                    operatorSession = cookie.substring("FG_SESSION=".length(), cookie.indexOf(';'));
                    operatorCsrf = new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("csrfToken").asText();
                }).assertDefault(context -> context.mutateRequest(request -> ((ObjectNode)request).put("bootstrapSecret", OPERATOR)));
    }
}
