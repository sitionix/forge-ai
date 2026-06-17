package com.sitionix.forgeai.domain.usecase;

import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisChatResponse;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;

public interface ManageJarvisInfrastructure {

    JarvisStatusView status();

    JarvisActionsView actions();

    JarvisCommandResultView command(JarvisCommandRequest command);

    JarvisChatResponse chat(JarvisChatRequest request);
}
