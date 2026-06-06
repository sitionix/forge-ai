package com.sitionix.forgeai.domain.repository;

import com.sitionix.forgeai.domain.model.codex.CodexProgressEvent;

public interface CodexProgressObserver {

    void onEvent(CodexProgressEvent event);
}
