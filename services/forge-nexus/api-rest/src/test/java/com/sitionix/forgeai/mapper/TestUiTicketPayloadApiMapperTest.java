package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementationSonarDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.TestUiPayload;
import com.sitionix.forgeai.domain.model.ticket.agentticket.UnitTestSonar;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestUiTicketPayloadApiMapperTest {

    private TestUiTicketPayloadApiMapper testUiTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.testUiTicketPayloadApiMapper = new TestUiTicketPayloadApiMapperImpl();
    }

    @Test
    void givenCompleteImplementFeLaneRequest_whenAsTestUiPayload_thenMapFields() {
        //given
        final CompleteImplementFeLaneRequestDTO source = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend flow")
                .changedFiles(List.of(ImplementFeChangedFileDTO.builder()
                        .path("apps/workspace/src/features/agent-details/page.tsx")
                        .reason("Updated frontend behavior")
                        .build()))
                .affectedSurfaces(List.of(ImplementFeAffectedSurfaceDTO.builder()
                        .type(ImplementFeAffectedSurfaceDTO.TypeEnum.PAGE)
                        .name("Agent details")
                        .summary("Updated page behavior")
                        .build()))
                .uiBehavior(List.of("User can submit create form"))
                .sonar(ImplementationSonarDTO.builder().issues(2).build())
                .build();

        //when
        final TestUiPayload actual = this.testUiTicketPayloadApiMapper.asTestUiPayload(source);

        //then
        final TestUiPayload expected = new TestUiPayload();
        expected.setTask("Write UI tests for frontend changed files in sitionix-spa");
        expected.setScope("sitionix-spa");
        expected.setSummary("Implemented frontend flow");
        expected.setChangedFiles(Set.of(new ImplementFeChangedFile(
                "apps/workspace/src/features/agent-details/page.tsx",
                "Updated frontend behavior"
        )));
        expected.setAffectedSurfaces(Set.of(new ImplementFeAffectedSurface(
                "PAGE",
                "Agent details",
                "Updated page behavior"
        )));
        expected.setUiBehavior(Set.of("User can submit create form"));
        expected.setSonar(new UnitTestSonar(null, 2));
        assertThat(actual).isEqualTo(expected);
    }
}
