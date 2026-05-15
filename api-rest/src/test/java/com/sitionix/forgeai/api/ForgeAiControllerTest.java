package com.sitionix.forgeai.api;

import com.app_afesox.fgaisox.api_first.dto.StartForgeRequestDTO;
import com.app_afesox.fgaisox.api_first.dto.StartForgeResponseDTO;
import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import com.sitionix.forgeai.mapper.ForgeAiApiMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgeAiControllerTest {

    private ForgeAiController forgeAiController;

    @Mock
    private StartForgeAiTask startForgeAiTask;

    @Mock
    private ForgeAiApiMapper forgeAiApiMapper;

    @Mock
    private TerminalTtyResolver terminalTtyResolver;

    @BeforeEach
    void setUp() {
        this.forgeAiController = new ForgeAiController(this.startForgeAiTask, this.forgeAiApiMapper, this.terminalTtyResolver);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.startForgeAiTask, this.forgeAiApiMapper, this.terminalTtyResolver);
    }

    @Test
    void givenValidStartForgeRequestDTO_whenStartForge_thenReturnCreatedResponseEntity() {
        //given
        final StartForgeRequestDTO requestDTO = mock(StartForgeRequestDTO.class);
        final ForgeAiStartCommand command = mock(ForgeAiStartCommand.class);
        final ForgeAiStartTask startedTask = mock(ForgeAiStartTask.class);
        final StartForgeResponseDTO responseDTO = mock(StartForgeResponseDTO.class);

        when(this.terminalTtyResolver.resolve()).thenReturn("/dev/ttys008");
        when(this.forgeAiApiMapper.asForgeAiStartCommand(requestDTO, "/dev/ttys008")).thenReturn(command);
        when(this.startForgeAiTask.execute(command)).thenReturn(startedTask);
        when(this.forgeAiApiMapper.asStartForgeResponseDto(startedTask)).thenReturn(responseDTO);

        //when
        final ResponseEntity<StartForgeResponseDTO> actual = this.forgeAiController.startForge(requestDTO);

        //then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(actual.getBody()).isEqualTo(responseDTO);
        verify(this.terminalTtyResolver).resolve();
        verify(this.forgeAiApiMapper).asForgeAiStartCommand(requestDTO, "/dev/ttys008");
        verify(this.startForgeAiTask).execute(command);
        verify(this.forgeAiApiMapper).asStartForgeResponseDto(startedTask);
    }
}
