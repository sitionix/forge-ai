package com.sitionix.forgeagent.application.runtime;
import com.sitionix.forgeagent.domain.model.AgentExecutionEventCandidate;
public interface AgentTurnEventSink {
    default void executionEvent(AgentExecutionEventCandidate event) { }
    default void eventCaptureCompleted(AgentExecutionEventCandidate event) { }
    default void eventCaptureDegraded(RuntimeException failure) { }
    static AgentTurnEventSink noop() { return new AgentTurnEventSink() { }; }
}
