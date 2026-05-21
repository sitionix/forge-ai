package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementBeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBeIntegrationFlowDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementBePersistenceChangeDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBeIntegrationFlow;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementBePersistenceChange;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestItPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestItTicketPayloadApiMapperTest {

    private TestItTicketPayloadApiMapper testItTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.testItTicketPayloadApiMapper = new TestItTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteImplementBeLaneRequest_whenAsTestItPayload_thenMapFields() {
        //given
        final CompleteImplementBeLaneRequestDTO source = CompleteImplementBeLaneRequestDTO.builder()
                .scope("automationservice-sox")
                .summary("summary")
                .integrationFlows(List.of(
                        ImplementBeIntegrationFlowDTO.builder().name("n1").method(ImplementBeIntegrationFlowDTO.MethodEnum.POST).path("/").operationId("op1").summary("s1").build()
                ))
                .persistenceChanges(List.of(
                        ImplementBePersistenceChangeDTO.builder().type(ImplementBePersistenceChangeDTO.TypeEnum.TABLE_CREATED).name("tbl").summary("ps").build()
                ))
                .build();

        //when
        final TestItPayload actual = this.testItTicketPayloadApiMapper.asTestItPayload(source);

        //then
        assertThat(actual).isEqualTo(this.getExpectedPayload());
    }

    private TestItPayload getExpectedPayload() {
        final TestItPayload payload = new TestItPayload();
        payload.setTask("Write integration tests for backend integration and persistence changes in automationservice-sox");
        payload.setScope("automationservice-sox");
        payload.setSummary("summary");
        payload.setIntegrationFlows(Set.of(new ImplementBeIntegrationFlow("n1", "POST", "/", "op1", "s1")));
        payload.setPersistenceChanges(Set.of(new ImplementBePersistenceChange("TABLE_CREATED", "tbl", "ps")));
        return payload;
    }
}
