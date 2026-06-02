package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.codex.CodexSession;
import com.sitionix.forgeai.domain.model.codex.CodexSessionStartCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnCommand;
import com.sitionix.forgeai.domain.model.codex.CodexTurnResponse;

public interface CodexSessionRepository {

    CodexSession openSession(CodexSessionStartCommand command);

    CodexTurnResponse submitTurn(String sessionId, CodexTurnCommand command);

    void closeSession(String sessionId);
}
