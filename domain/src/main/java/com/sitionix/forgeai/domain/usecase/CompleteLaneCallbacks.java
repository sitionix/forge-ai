package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.lanecompletion.LaneCompletionCommands;

public interface CompleteLaneCallbacks {

    void completeAnalyzerLane(LaneCompletionCommands.Analyzer command);

    void completeArchitectLane(LaneCompletionCommands.Architect command);

    void completeApiLane(LaneCompletionCommands.Api command);

    void completeImplementBeLane(LaneCompletionCommands.ImplementBe command);

    void completeImplementFeLane(LaneCompletionCommands.ImplementFe command);

    void completeQaLeadLane(LaneCompletionCommands.QaLead command);

    void completeItTestLane(LaneCompletionCommands.ItTest command);

    void completeUiTestLane(LaneCompletionCommands.UiTest command);

    void completeUnitTestLane(LaneCompletionCommands.UnitTest command);

    void completeReviewerLane(LaneCompletionCommands.Reviewer command);
}
