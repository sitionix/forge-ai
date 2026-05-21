package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBePersistenceChangeDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import java.util.LinkedHashSet;
import java.util.Set;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TestItTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write integration tests for backend integration and persistence changes in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationFlows", expression = "java(this.asIntegrationFlows(source.getIntegrationFlows()))")
    @Mapping(target = "persistenceChanges", expression = "java(this.asPersistenceChanges(source.getPersistenceChanges()))")
    TestItPayload asTestItPayload(CompleteImplementBeLaneRequestDTO source);

    default Set<String> asIntegrationFlows(final java.util.List<ImplementBeIntegrationFlowDTO> source) {
        return source.stream()
                .map(value -> value.getName()
                        + " | " + value.getMethod()
                        + " " + value.getPath()
                        + " | " + value.getOperationId()
                        + " | " + value.getSummary())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    default Set<String> asPersistenceChanges(final java.util.List<ImplementBePersistenceChangeDTO> source) {
        return source.stream()
                .map(value -> value.getType() + " | " + value.getName() + " | " + value.getSummary())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
