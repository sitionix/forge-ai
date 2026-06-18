package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.OperatorExecutionDTO;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecution;
import com.sitionix.forgeai.domain.model.laneexecution.LaneExecutionStatus;
import com.sitionix.forgeai.domain.usecase.ManageLaneExecutions;
import com.sitionix.forgeai.mapper.ForgeAiOperatorApiMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiOperatorControllerTest {

    private static final UUID EXECUTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ManageLaneExecutions manageLaneExecutions;
    @Mock
    private ForgeAiOperatorApiMapper forgeAiOperatorApiMapper;

    private ForgeAiOperatorController controller;

    @BeforeEach
    void setUp() {
        this.controller = new ForgeAiOperatorController(this.manageLaneExecutions, this.forgeAiOperatorApiMapper);
    }

    @Test
    void givenExecutionId_whenInterruptOperatorExecution_thenDelegateToUseCase() {
        final LaneExecution execution = LaneExecution.builder()
                .id(EXECUTION_ID)
                .status(LaneExecutionStatus.INTERRUPTED)
                .build();
        final OperatorExecutionDTO dto = OperatorExecutionDTO.builder()
                .executionId(EXECUTION_ID)
                .status("INTERRUPTED")
                .build();
        when(this.manageLaneExecutions.interrupt(EXECUTION_ID)).thenReturn(execution);
        when(this.forgeAiOperatorApiMapper.asOperatorExecution(execution)).thenReturn(dto);

        final ResponseEntity<OperatorExecutionDTO> result = this.controller.interruptOperatorExecution(EXECUTION_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(dto);
        verify(this.manageLaneExecutions).interrupt(EXECUTION_ID);
    }
}
