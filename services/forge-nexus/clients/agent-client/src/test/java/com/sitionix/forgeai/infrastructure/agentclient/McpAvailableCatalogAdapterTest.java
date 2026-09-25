package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class McpAvailableCatalogAdapterTest {
    @Test
    void executesTypedCallThenDelegatesMapping() {
        ForgeAgentHttpClient client = mock(ForgeAgentHttpClient.class);
        ForgeAgentClientCallExecutor executor = mock(ForgeAgentClientCallExecutor.class);
        McpClientMapper mapper = mock(McpClientMapper.class);
        var inbound = new McpAvailablePageInbound(List.of(), "next");
        var domain = new McpAvailablePage(List.of(), "next");
        when(client.listAvailableMcp("search", "cursor", 20)).thenReturn(inbound);
        when(mapper.toDomain(inbound)).thenReturn(domain);
        doAnswer(call -> ((Supplier<?>) call.getArgument(0)).get()).when(executor).execute(any());

        var result = new McpAvailableCatalogAdapter(client, executor, mapper)
                .list("search", "cursor", 20);

        assertThat(result).isSameAs(domain);
        var order = inOrder(executor, client, mapper);
        order.verify(executor).execute(any());
        order.verify(client).listAvailableMcp("search", "cursor", 20);
        order.verify(mapper).toDomain(inbound);
    }
}
