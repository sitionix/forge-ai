package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;
import java.time.Duration;

public interface CodexSessionRepository {

    CodexSession openSession(CodexSessionStartCommand command);

    CodexTurnResponse submitTurn(String sessionId, CodexTurnCommand command);

    void interruptTurn(String sessionId, String turnId, Duration timeout);

    void closeSession(String sessionId);
}
