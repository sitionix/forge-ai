package com.sitionix.forgeagent.infrastructure.codex;

final class CodexProtocol {

    static final String INITIALIZE = "initialize";
    static final String INITIALIZED = "initialized";
    static final String THREAD_START = "thread/start";
    static final String THREAD_RESUME = "thread/resume";
    static final String TURN_START = "turn/start";
    static final String TURN_STARTED = "turn/started";
    static final String TURN_COMPLETED = "turn/completed";
    static final String THREAD_STATUS_CHANGED = "thread/status/changed";
    static final String TURN_INTERRUPT = "turn/interrupt";
    static final String ITEM_STARTED = "item/started";
    static final String ITEM_COMPLETED = "item/completed";
    static final String ERROR = "error";
    static final String COMMAND_APPROVAL = "item/commandExecution/requestApproval";
    static final String FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval";

    static final String APPROVAL_POLICY_NEVER = "never";
    static final String SANDBOX_WORKSPACE_WRITE = "workspace-write";

    private CodexProtocol() {
    }
}
