package com.sitionix.forgeai.infrastructure.codexcli.adapter;

import com.sitionix.forgeai.domain.model.ForgeAiStartCommand;
import com.sitionix.forgeai.domain.model.ForgeAiStartTask;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class CodexCliTaskRunnerTest {

    private CodexCliTaskRunner codexCliTaskRunner;

    @Mock
    private CodexCliJsonClient codexCliJsonClient;

    @BeforeEach
    void setUp() {
        this.codexCliTaskRunner = new CodexCliTaskRunner(this.codexCliJsonClient);
    }

    @AfterEach
    void tearDown() {
        verifyNoMoreInteractions(this.codexCliJsonClient);
    }

    @Test
    void givenCommand_whenRun_thenSubmitAndReturnSubmittedTask() {
        //given
        final ForgeAiStartCommand command = ForgeAiStartCommand.builder()
                .scope("forgeai")
                .ticket("SITIONIX-1")
                .task("do task")
                .serviceIds(List.of("athssox", "forgeai"))
                .sourceTerminalTty("/dev/ttys008")
                .build();

        //when
        final ForgeAiStartTask actual = this.codexCliTaskRunner.run(command);

        //then
        assertThat(actual.getId()).isNotBlank();
        assertThat(actual.getTicket()).isEqualTo("SITIONIX-1");
        assertThat(actual.getTask()).isEqualTo("do task");
        assertThat(actual.getScope()).isEqualTo("forgeai");
        assertThat(actual.getStatus()).isEqualTo("SUBMITTED");
        assertThat(actual.getCreatedAt()).isNotNull();
        verify(this.codexCliJsonClient).submit(command, "/dev/ttys008");
    }
}
