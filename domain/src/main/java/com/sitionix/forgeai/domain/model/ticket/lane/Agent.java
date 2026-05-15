package com.sitionix.forgeai.domain.model.ticket.lane;

import com.sitionix.forgeai.domain.port.AgentPropertiesProvider;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@RequiredArgsConstructor
public enum Agent {
    ANALYZER("analyzer"),
    ARCHITECT("architect"),
    API("api"),
    EVENT("event"),
    QA_LEAD("qa_lead"),
    IMPLEMENT_BE("implement_be"),
    IMPLEMENT_FE("implement_fe"),
    TEST_UNIT("test_unit"),
    TEST_IT("test_it"),
    TEST_UI("test_ui");

    private final String id;

    @Setter
    private AgentPropertiesProvider.AgentConfigView info;

    public AgentPropertiesProvider.AgentConfigView getInfo() {
        if (this.info == null) {
            throw new IllegalStateException("No agent info configured for agent: " + this.id);
        }
        return this.info;
    }

    public static Agent byId(final String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent id: " + id));
    }
}
