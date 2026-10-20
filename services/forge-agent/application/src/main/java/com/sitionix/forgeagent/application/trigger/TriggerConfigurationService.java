package com.sitionix.forgeagent.application.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.*;
import com.sitionix.forgeagent.domain.exception.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TriggerConfigurationService {
    private final McpConnectionRepository connections;
    private final ForgeInstanceIdentityRepository identity;
    private final McpToolInventoryRepository inventory;
    private final TriggerConfigurationRepository configurations;
    private final TriggerConfigurationValidator validator;

    public TriggerConfigurationService(McpConnectionRepository connections, ForgeInstanceIdentityRepository identity,
            McpToolInventoryRepository inventory, TriggerConfigurationRepository configurations,
            TriggerConfigurationValidator validator, ObjectMapper json) {
        this.connections=connections; this.identity=identity; this.inventory=inventory;
        this.configurations=configurations; this.validator=validator;
    }
    public List<TriggerConfiguration> list(UUID connectionId) {
        requireConnection(connectionId, false); return configurations.findSummaries(connectionId);
    }
    public TriggerConfigurationDocument get(UUID connectionId, UUID id) {
        requireConnection(connectionId, false); return requireDocument(connectionId,id);
    }
    public TriggerConfigurationDocument create(UUID connectionId, String name, JsonNode definition) {
        return save(connectionId,UUID.randomUUID(),name,definition,false);
    }
    public TriggerConfigurationDocument update(UUID connectionId, UUID id, String name, JsonNode definition) {
        return save(connectionId,id,name,definition,true);
    }
    private TriggerConfigurationDocument save(UUID connectionId, UUID id, String name, JsonNode definition, boolean update) {
        var connection=requireConnection(connectionId,true);
        if(update) requireDocument(connectionId,id);
        if(name==null || name.isBlank() || name.strip().length()>200)
            throw new ValidationException("INVALID_TRIGGER_CONFIGURATION","Provide a name of 1–200 characters.");
        validator.validate(definition,inventory.list(connection.installationId(),connectionId),connection.allowedTools());
        var document=new TriggerConfigurationDocument(id,connectionId,name.strip(),definition.toString());
        configurations.save(document); return document;
    }
    private McpConnection requireConnection(UUID id, boolean lock) {
        UUID owner=identity.getOrCreate();
        if(lock && !configurations.lockConnection(owner,id)) throw missingConnection();
        return connections.findById(owner,id).orElseThrow(TriggerConfigurationService::missingConnection);
    }
    private TriggerConfigurationDocument requireDocument(UUID connection, UUID id) {
        return configurations.findById(connection,id).orElseThrow(()->new NotFoundException(
                "TRIGGER_CONFIGURATION_NOT_FOUND","Trigger configuration was not found on this connection."));
    }
    private static NotFoundException missingConnection() {
        return new NotFoundException("MCP_CONNECTION_NOT_FOUND","MCP connection was not found.");
    }
}
