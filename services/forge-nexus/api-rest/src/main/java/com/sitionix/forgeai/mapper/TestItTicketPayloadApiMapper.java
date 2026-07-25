package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBePersistenceChangeDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementationSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePersistenceChange;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import java.util.List;
import java.util.Set;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public abstract class TestItTicketPayloadApiMapper {

    @Mapping(target = "task", expression = "java(\"Write integration tests for backend integration and persistence changes in \" + source.getScope())")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "summary", source = "summary")
    @Mapping(target = "integrationFlows", source = "integrationFlows")
    @Mapping(target = "persistenceChanges", source = "persistenceChanges")
    @Mapping(target = "sonar", source = "sonar")
    public abstract TestItPayload asTestItPayload(CompleteImplementBeLaneRequestDTO source);

    @IterableMapping(elementTargetType = ImplementBeIntegrationFlow.class)
    public abstract Set<ImplementBeIntegrationFlow> asIntegrationFlows(List<ImplementBeIntegrationFlowDTO> source);

    public abstract ImplementBeIntegrationFlow asIntegrationFlow(ImplementBeIntegrationFlowDTO source);

    @IterableMapping(elementTargetType = ImplementBePersistenceChange.class)
    public abstract Set<ImplementBePersistenceChange> asPersistenceChanges(List<ImplementBePersistenceChangeDTO> source);

    public abstract ImplementBePersistenceChange asPersistenceChange(ImplementBePersistenceChangeDTO source);

    public abstract UnitTestSonar asUnitTestSonar(ImplementationSonarDTO source);
}
