package com.sitionix.forgeai.mapper;

import com.app_afesox.fgaisox.api_first.dto.CompleteImplementFeLaneRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeAffectedSurfaceDTO;
import com.app_afesox.fgaisox.api_first.dto.ImplementFeChangedFileDTO;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeAffectedSurface;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeChangedFile;
import com.sitionix.forgeai.domain.model.ticket.agentticket.ImplementFeCompletionPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementFeCompletionTicketPayloadApiMapperTest {

    private ImplementFeCompletionTicketPayloadApiMapper implementFeCompletionTicketPayloadApiMapper;

    @BeforeEach
    void setUp() {
        this.implementFeCompletionTicketPayloadApiMapper = new ImplementFeCompletionTicketPayloadApiMapperImpl();
    }

    @Test
    void givenImplementFeCompletionRequest_whenAsImplementFeCompletionPayload_thenMapFields() {
        //given
        final CompleteImplementFeLaneRequestDTO source = CompleteImplementFeLaneRequestDTO.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .changedFiles(List.of(ImplementFeChangedFileDTO.builder()
                        .path("apps/workspace/src/features/agent-details/page.tsx")
                        .reason("Updated page behavior for assigned flow.")
                        .build()))
                .affectedSurfaces(List.of(ImplementFeAffectedSurfaceDTO.builder()
                        .type(ImplementFeAffectedSurfaceDTO.TypeEnum.PAGE)
                        .name("Agent details")
                        .summary("Updated user-facing behavior on the page.")
                        .build()))
                .uiBehavior(List.of("User can perform the assigned action from the updated UI."))
                .build();

        //when
        final ImplementFeCompletionPayload actual = this.implementFeCompletionTicketPayloadApiMapper.asImplementFeCompletionPayload(source);

        //then
        assertThat(actual).isEqualTo(ImplementFeCompletionPayload.builder()
                .scope("sitionix-spa")
                .summary("Implemented frontend changes for assigned flow.")
                .changedFiles(List.of(new ImplementFeChangedFile(
                        "apps/workspace/src/features/agent-details/page.tsx",
                        "Updated page behavior for assigned flow."
                )))
                .affectedSurfaces(List.of(new ImplementFeAffectedSurface(
                        "PAGE",
                        "Agent details",
                        "Updated user-facing behavior on the page."
                )))
                .uiBehavior(List.of("User can perform the assigned action from the updated UI."))
                .build());
    }
}
