package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import com.sitionix.forgeai.domain.port.ForgeAiTaskRunner;
import com.sitionix.forgeai.domain.usecase.StartForgeAiTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartForgeAiTaskUseCaseTest {

    private StartForgeAiTask startForgeAiTask;

    @Mock
    private ForgeAiTaskRunner forgeAiTaskRunner;

    @BeforeEach
    void setUp() {
        this.startForgeAiTask = new StartForgeAiTaskUseCase(this.forgeAiTaskRunner);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.forgeAiTaskRunner);
    }

    @Test
    void givenStartCommand_whenExecute_thenReturnStartedTask() {
        //given
        final ForgeAiStartCommand command = ForgeAiStartCommand.builder().ticket("SITIONIX-1").build();
        final ForgeAiStartTask expected = ForgeAiStartTask.builder().id("id").build();
        when(this.forgeAiTaskRunner.run(command)).thenReturn(expected);

        //when
        final ForgeAiStartTask actual = this.startForgeAiTask.execute(command);

        //then
        assertThat(actual).isEqualTo(expected);
        verify(this.forgeAiTaskRunner).run(command);
    }
}
