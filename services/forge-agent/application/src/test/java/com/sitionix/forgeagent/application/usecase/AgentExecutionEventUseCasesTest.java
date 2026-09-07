package com.sitionix.forgeagent.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCaptureStatus;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventPage;
import com.sitionix.forgeagent.domain.port.AgentExecutionEventRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentExecutionEventUseCasesTest {
    private static final UUID TURN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private AgentExecutionEventRepository repository;

    @Test
    void returnsBoundedPageFromRepository() {
        final AgentExecutionEventPage page = new AgentExecutionEventPage(
                TURN_ID, AgentExecutionEventCaptureStatus.ACTIVE, List.of(), 4, 4, false);
        when(this.repository.findPage(TURN_ID, 4, 200)).thenReturn(Optional.of(page));

        final AgentExecutionEventPage result = new AgentExecutionEventUseCases(this.repository)
                .page(TURN_ID, 4, 200);

        assertThat(result).isSameAs(page);
        verify(this.repository).findPage(TURN_ID, 4, 200);
    }

    @Test
    void rejectsNegativeCursorAndOversizedPage() {
        final AgentExecutionEventUseCases useCases = new AgentExecutionEventUseCases(this.repository);

        assertThatThrownBy(() -> useCases.page(TURN_ID, -1, 10))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Event cursor must not be negative.");
        assertThatThrownBy(() -> useCases.page(TURN_ID, 0, 201))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Event page size must be between 1 and 200.");
    }
}
