package com.sitionix.forgeai.infrastructure.agentclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeai.domain.model.mcp.McpAvailablePage;
import com.sitionix.forgeai.infrastructure.agentclient.dto.McpAvailablePageInbound;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpAvailableCatalogAdapterTest {
    @Test
    void delegatesExecutorResponseToMapper() {
        ForgeAgentHttpClient client = mock(ForgeAgentHttpClient.class);
        ForgeAgentClientCallExecutor executor = mock(ForgeAgentClientCallExecutor.class);
        McpClientMapper mapper = mock(McpClientMapper.class);
        var inbound = new McpAvailablePageInbound(List.of(), "next");
        var domain = new McpAvailablePage(List.of(), "next");
        when(executor.execute(any())).thenReturn(inbound);
        when(mapper.toDomain(inbound)).thenReturn(domain);

        var result = new McpAvailableCatalogAdapter(client, executor, mapper)
                .list("search", "cursor", 20);

        assertThat(result).isSameAs(domain);
        verify(executor).execute(any());
        verify(mapper).toDomain(inbound);
    }
}
