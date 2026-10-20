package com.sitionix.forgeagent.application.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.*;
import java.util.*;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TriggerConfigurationServiceTest {
    @Test void savesAndLoadsOnlyInsideTheOwningConnectionAndKeepsPriorVersionOnInvalidUpdate() throws Exception {
        UUID installation=UUID.randomUUID(), connectionId=UUID.randomUUID();
        var connections=mock(McpConnectionRepository.class);var inventory=mock(McpToolInventoryRepository.class);
        var identity=mock(ForgeInstanceIdentityRepository.class);var repository=mock(TriggerConfigurationRepository.class);
        var now=Instant.now();
        var connection=new McpConnection(connectionId,installation,"Example",URI.create("https://example.test/mcp"),McpAuthType.NONE,false,
                McpProjectAccess.all(),Set.of(new McpAllowedTool("read","sha")),false,now,now,null,null,null,null);
        when(identity.getOrCreate()).thenReturn(installation);
        when(connections.findById(installation,connectionId)).thenReturn(Optional.of(connection));
        when(repository.lockConnection(installation,connectionId)).thenReturn(true);
        when(inventory.list(installation,connectionId)).thenReturn(List.of(new McpToolSummary("read",null,"sha",Map.of("type","object"),null)));
        var json=new ObjectMapper();var service=new TriggerConfigurationService(connections,identity,inventory,repository,new TriggerConfigurationValidator(json),json);
        var definition=json.readTree("""
            {"version":1,"steps":[{"id":"11111111-1111-4111-8111-111111111111","toolName":"read","schemaFingerprint":"sha","arguments":{},"bindings":[]}],"result":{"stepId":"11111111-1111-4111-8111-111111111111","pointer":""}}
            """);
        var saved=service.create(connectionId," Example ",definition);
        assertThat(saved.name()).isEqualTo("Example");assertThat(json.readTree(saved.definitionJson())).isEqualTo(definition);
        when(repository.findById(connectionId,saved.id())).thenReturn(Optional.of(saved));
        assertThat(service.get(connectionId,saved.id())).isEqualTo(saved);
        UUID foreign=UUID.randomUUID();when(connections.findById(installation,foreign)).thenReturn(Optional.of(connection));
        assertThatThrownBy(()->service.get(foreign,saved.id())).isInstanceOf(NotFoundException.class);
        clearInvocations(repository);
        assertThatThrownBy(()->service.update(connectionId,saved.id(),"Changed",json.readTree("{}"))).isInstanceOf(ValidationException.class);
        verify(repository,never()).save(any());
    }
}
